package com.example.monkey.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.example.monkey.order.application.dto.OrderResponseDto;
import com.example.monkey.order.application.observability.BusinessMetricsService;
import com.example.monkey.order.domain.OrderIdempotencyKeyStore;
import com.example.monkey.order.domain.OrderIdempotencyStore;
import com.example.monkey.order.domain.OrderIdempotencyStore.IdempotencyReservationRecord;
import com.example.monkey.order.domain.OrderLockManager;
import com.example.monkey.order.domain.OrderStore;
import com.example.monkey.order.domain.OrderStore.AddressRecord;
import com.example.monkey.order.domain.OrderStore.BuyerRecord;
import com.example.monkey.order.domain.OrderStore.OrderPage;
import com.example.monkey.order.domain.OrderStore.OrderPageRequest;
import com.example.monkey.order.domain.OrderStore.OrderRecord;
import com.example.monkey.order.domain.OrderStore.ProductRecord;
import com.example.monkey.order.infrastructure.SpringStateMachineOrderTransitionResolver;
import com.example.monkey.shared.application.observability.AuditService;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.shared.domain.storage.ImageReferenceService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

class OrderConcurrencyTest {

    private static final Long PRODUCT_ID = 7L;

    @Test
    void concurrentUniqueOrderKeysDoNotOversellAvailableStock() throws Exception {
        int initialStock = 3;
        int concurrency = 16;
        InMemoryOrderStore orderStore = new InMemoryOrderStore(PRODUCT_ID, initialStock);
        OrderService orderService = orderService(
                orderStore,
                new OrderIdempotencyService(
                        new NoopOrderIdempotencyKeyStore(), new InMemoryOrderIdempotencyStore(), Duration.ofHours(24)));

        List<Attempt> attempts = runConcurrently(concurrency, index -> {
            long userId = 1_000L + index;
            orderStore.registerBuyer(userId);
            orderStore.registerAddress(userId, userId);
            return Attempt.success(orderService.createOrder(userId, PRODUCT_ID, userId, "order-key-" + index));
        });

        List<Attempt> successful = attempts.stream().filter(Attempt::successful).toList();
        List<Attempt> outOfStock = attempts.stream()
                .filter(attempt -> attempt.errorCode() == ErrorCode.OUT_OF_STOCK)
                .toList();
        assertThat(successful).hasSize(initialStock);
        assertThat(outOfStock).hasSize(concurrency - initialStock);
        assertThat(successful).extracting(attempt -> attempt.response().id()).doesNotHaveDuplicates();
        assertThat(successful)
                .extracting(attempt -> attempt.response().orderNo())
                .doesNotHaveDuplicates();
        assertThat(orderStore.remainingStock()).isZero();
        assertThat(orderStore.savedOrderCount()).isEqualTo(initialStock);
        assertThat(orderStore.successfulStockDeductions()).isEqualTo(initialStock);
    }

    @Test
    void concurrentDuplicateIdempotencyKeyReturnsOneOrderWithoutSecondStockDeduction() throws Exception {
        int concurrency = 12;
        InMemoryOrderStore orderStore = new InMemoryOrderStore(PRODUCT_ID, concurrency);
        orderStore.registerBuyer(42L);
        orderStore.registerAddress(3L, 42L);
        OrderService orderService = orderService(
                orderStore,
                new OrderIdempotencyService(
                        new NoopOrderIdempotencyKeyStore(), new InMemoryOrderIdempotencyStore(), Duration.ofHours(24)));

        List<Attempt> attempts = runConcurrently(
                concurrency,
                ignored -> Attempt.success(orderService.createOrder(42L, PRODUCT_ID, 3L, "same-order-key")));

        assertThat(attempts).allMatch(Attempt::successful);
        assertThat(attempts.stream()
                        .map(attempt -> attempt.response().id())
                        .distinct()
                        .toList())
                .containsExactly(1L);
        assertThat(orderStore.remainingStock()).isEqualTo(concurrency - 1);
        assertThat(orderStore.savedOrderCount()).isEqualTo(1);
        assertThat(orderStore.successfulStockDeductions()).isEqualTo(1);
    }

    private static OrderService orderService(
            InMemoryOrderStore orderStore, OrderIdempotencyService orderIdempotencyService) {
        AtomicLong orderNoSequence = new AtomicLong(1_000L);
        return new OrderService(
                orderStore,
                () -> "ORD" + orderNoSequence.getAndIncrement(),
                orderIdempotencyService,
                new LocalOrderLockManager(),
                new SpringStateMachineOrderTransitionResolver(),
                immediateTransactions(),
                new NoopImageReferenceService(),
                new BusinessMetricsService(new SimpleMeterRegistry(), () -> orderStore.savedOrderCount()),
                mock(AuditService.class));
    }

    private static List<Attempt> runConcurrently(int concurrency, IntFunction<Attempt> operation)
            throws InterruptedException, ExecutionException, TimeoutException {
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Attempt>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < concurrency; i++) {
                int index = i;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting for concurrent start");
                    }
                    try {
                        return operation.apply(index);
                    } catch (BusinessException e) {
                        return Attempt.failure(e.errorCode());
                    }
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Attempt> attempts = new ArrayList<>();
            for (Future<Attempt> future : futures) {
                attempts.add(future.get(10, TimeUnit.SECONDS));
            }
            return attempts;
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static TransactionOperations immediateTransactions() {
        return new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(new SimpleTransactionStatus());
            }
        };
    }

    private record Attempt(OrderResponseDto response, ErrorCode errorCode) {
        static Attempt success(OrderResponseDto response) {
            return new Attempt(response, null);
        }

        static Attempt failure(ErrorCode errorCode) {
            return new Attempt(null, errorCode);
        }

        boolean successful() {
            return response != null;
        }
    }

    private record IdempotencyKey(Long userId, String idempotencyKey) {}

    private static final class NoopOrderIdempotencyKeyStore implements OrderIdempotencyKeyStore {

        @Override
        public void reserve(Long userId, String idempotencyKey, String requestHash, Duration ttl) {}
    }

    private static final class InMemoryOrderIdempotencyStore implements OrderIdempotencyStore {

        private final ConcurrentMap<IdempotencyKey, IdempotencyReservationRecord> records = new ConcurrentHashMap<>();
        private final AtomicLong ids = new AtomicLong(1L);

        @Override
        public boolean reserve(Long userId, String idempotencyKey, String requestHash, LocalDateTime expiresAt) {
            IdempotencyKey key = new IdempotencyKey(userId, idempotencyKey);
            IdempotencyReservationRecord record = new IdempotencyReservationRecord(
                    ids.getAndIncrement(),
                    userId,
                    idempotencyKey,
                    requestHash,
                    null,
                    IdempotencyReservationRecord.STATUS_PROCESSING,
                    LocalDateTime.now(),
                    expiresAt);
            return records.putIfAbsent(key, record) == null;
        }

        @Override
        public Optional<IdempotencyReservationRecord> find(Long userId, String idempotencyKey) {
            return Optional.ofNullable(records.get(new IdempotencyKey(userId, idempotencyKey)));
        }

        @Override
        public void complete(Long userId, String idempotencyKey, Long orderId) {
            records.computeIfPresent(
                    new IdempotencyKey(userId, idempotencyKey),
                    (key, record) -> new IdempotencyReservationRecord(
                            record.id(),
                            record.userId(),
                            record.idempotencyKey(),
                            record.requestHash(),
                            orderId,
                            IdempotencyReservationRecord.STATUS_COMPLETED,
                            record.createdAt(),
                            record.expiresAt()));
        }
    }

    private static final class LocalOrderLockManager implements OrderLockManager {

        private final ConcurrentMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

        @Override
        public <T> T withCreateOrderLock(Long userId, Long productId, java.util.function.Supplier<T> operation) {
            ReentrantLock lock = locks.computeIfAbsent(userId + ":" + productId, ignored -> new ReentrantLock());
            lock.lock();
            try {
                return operation.get();
            } finally {
                lock.unlock();
            }
        }
    }

    private static final class InMemoryOrderStore implements OrderStore {

        private final Long productId;
        private final AtomicInteger stock;
        private final AtomicInteger successfulStockDeductions = new AtomicInteger();
        private final AtomicLong orderIds = new AtomicLong(1L);
        private final ConcurrentMap<Long, OrderRecord> orders = new ConcurrentHashMap<>();
        private final ConcurrentMap<Long, Long> addressOwners = new ConcurrentHashMap<>();
        private final ConcurrentMap<Long, BuyerRecord> buyers = new ConcurrentHashMap<>();

        private InMemoryOrderStore(Long productId, int stock) {
            this.productId = productId;
            this.stock = new AtomicInteger(stock);
        }

        void registerBuyer(Long userId) {
            buyers.putIfAbsent(userId, new BuyerRecord(userId, "buyer-" + userId, "/images/avatar/buyer.png"));
        }

        void registerAddress(Long addressId, Long userId) {
            addressOwners.putIfAbsent(addressId, userId);
        }

        int remainingStock() {
            return stock.get();
        }

        int successfulStockDeductions() {
            return successfulStockDeductions.get();
        }

        int savedOrderCount() {
            return orders.size();
        }

        @Override
        public OrderPage findVisibleByUser(Long userId, OrderPageRequest request) {
            throw new UnsupportedOperationException("Not needed for order creation concurrency tests");
        }

        @Override
        public OrderPage findAll(OrderPageRequest request) {
            throw new UnsupportedOperationException("Not needed for order creation concurrency tests");
        }

        @Override
        public Optional<OrderRecord> findById(Long id) {
            return Optional.ofNullable(orders.get(id));
        }

        @Override
        public Optional<OrderRecord> findVisibleByIdAndUserId(Long id, Long userId) {
            return Optional.empty();
        }

        @Override
        public Optional<ProductRecord> findProductById(Long requestedProductId) {
            if (!productId.equals(requestedProductId)) {
                return Optional.empty();
            }
            return Optional.of(new ProductRecord(
                    productId, "Momo", "/images/product/momo.png", new BigDecimal("199.99"), "calm", stock.get()));
        }

        @Override
        public Optional<AddressRecord> findAddressById(Long addressId) {
            return Optional.ofNullable(addressOwners.get(addressId))
                    .map(userId -> new AddressRecord(addressId, userId, "Ada", "13800138000", "Hangzhou"));
        }

        @Override
        public Optional<BuyerRecord> findBuyerById(Long userId) {
            return Optional.ofNullable(buyers.get(userId));
        }

        @Override
        public boolean deductProductStock(Long requestedProductId) {
            if (!productId.equals(requestedProductId)) {
                return false;
            }
            while (true) {
                int currentStock = stock.get();
                if (currentStock <= 0) {
                    return false;
                }
                if (stock.compareAndSet(currentStock, currentStock - 1)) {
                    successfulStockDeductions.incrementAndGet();
                    return true;
                }
            }
        }

        @Override
        public OrderRecord savePlacedOrder(OrderRecord order) {
            Long orderId = orderIds.getAndIncrement();
            OrderRecord savedOrder = withOrderId(order, orderId);
            orders.put(orderId, savedOrder);
            return savedOrder;
        }

        @Override
        public void hideFromUser(Long orderId) {
            throw new UnsupportedOperationException("Not needed for order creation concurrency tests");
        }

        @Override
        public boolean recordStockRestore(Long orderId, Long productId) {
            throw new UnsupportedOperationException("Not needed for order creation concurrency tests");
        }

        @Override
        public boolean restoreProductStock(Long productId) {
            throw new UnsupportedOperationException("Not needed for order creation concurrency tests");
        }

        @Override
        public int transitionStatus(
                Long orderId, String expectedStatus, String nextStatus, LocalDateTime shippingTime) {
            throw new UnsupportedOperationException("Not needed for order creation concurrency tests");
        }

        private static OrderRecord withOrderId(OrderRecord order, Long orderId) {
            return new OrderRecord(
                    orderId,
                    order.orderNo(),
                    order.userId(),
                    order.buyerName(),
                    order.buyerAvatar(),
                    order.productId(),
                    order.productName(),
                    order.productImage(),
                    order.price(),
                    order.description(),
                    order.receiverName(),
                    order.receiverPhone(),
                    order.addressSnapshot(),
                    order.shippingTime(),
                    order.status(),
                    LocalDateTime.now(),
                    order.userHidden());
        }
    }

    private static final class NoopImageReferenceService implements ImageReferenceService {

        @Override
        public void retain(String imagePath) {}

        @Override
        public void release(String imagePath) {}

        @Override
        public long referenceCount(String imagePath) {
            return 0;
        }

        @Override
        public void clear() {}
    }
}
