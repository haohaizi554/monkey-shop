package com.example.monkey.service;

import com.example.monkey.assembler.OrderDtoAssembler;
import com.example.monkey.domain.order.OrderEvent;
import com.example.monkey.domain.order.OrderIdempotencyStore.IdempotencyReservationRecord;
import com.example.monkey.domain.order.OrderNumberGenerator;
import com.example.monkey.domain.order.OrderStatus;
import com.example.monkey.domain.order.OrderStore;
import com.example.monkey.domain.order.OrderStore.AddressRecord;
import com.example.monkey.domain.order.OrderStore.BuyerRecord;
import com.example.monkey.domain.order.OrderStore.OrderPage;
import com.example.monkey.domain.order.OrderStore.OrderPageRequest;
import com.example.monkey.domain.order.OrderStore.OrderRecord;
import com.example.monkey.domain.order.OrderStore.ProductRecord;
import com.example.monkey.domain.order.OrderTransitionPolicy;
import com.example.monkey.domain.order.OrderTransitionResolver;
import com.example.monkey.domain.storage.ImageReferenceService;
import com.example.monkey.dto.OrderResponseDto;
import com.example.monkey.dto.PageResponseDto;
import com.example.monkey.shared.api.ErrorCode;
import com.example.monkey.shared.exception.BusinessException;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.util.StringUtils;

@Service
public class OrderService {

    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;
    private static final Pattern IDEMPOTENCY_KEY_PATTERN = Pattern.compile("[A-Za-z0-9._:-]+");

    private final OrderStore orderStore;
    private final OrderNumberGenerator orderNumberGenerator;
    private final OrderIdempotencyService orderIdempotencyService;
    private final OrderDistributedLockService orderDistributedLockService;
    private final OrderTransitionResolver orderTransitionResolver;
    private final TransactionOperations transactionOperations;
    private final ImageReferenceService imageReferenceService;
    private final BusinessMetricsService businessMetricsService;

    public OrderService(
            OrderStore orderStore,
            OrderNumberGenerator orderNumberGenerator,
            OrderIdempotencyService orderIdempotencyService,
            OrderDistributedLockService orderDistributedLockService,
            OrderTransitionResolver orderTransitionResolver,
            TransactionOperations transactionOperations,
            ImageReferenceService imageReferenceService,
            BusinessMetricsService businessMetricsService) {
        this.orderStore = orderStore;
        this.orderNumberGenerator = orderNumberGenerator;
        this.orderIdempotencyService = orderIdempotencyService;
        this.orderDistributedLockService = orderDistributedLockService;
        this.orderTransitionResolver = orderTransitionResolver;
        this.transactionOperations = transactionOperations;
        this.imageReferenceService = imageReferenceService;
        this.businessMetricsService = businessMetricsService;
    }

    @Transactional(readOnly = true)
    public List<OrderResponseDto> findOrdersForUser(Long userId) {
        return orderStore.findVisibleByUserOrderByCreateTimeDesc(userId).stream()
                .map(OrderDtoAssembler::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public PageResponseDto<OrderResponseDto> findOrdersForUser(Long userId, OrderPageRequest pageRequest) {
        OrderPage page = orderStore.findVisibleByUser(userId, pageRequest);
        return toPageResponse(page);
    }

    @Transactional(readOnly = true)
    public List<OrderResponseDto> findAllOrders() {
        return orderStore.findAllOrderByCreateTimeDesc().stream()
                .map(OrderDtoAssembler::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public PageResponseDto<OrderResponseDto> findAllOrders(OrderPageRequest pageRequest) {
        return toPageResponse(orderStore.findAll(pageRequest));
    }

    @WithSpan("order.create")
    public OrderResponseDto createOrder(Long userId, Long monkeyId, Long addressId, String idempotencyKey) {
        return businessMetricsService.recordOrderCreate(() -> {
            String normalizedIdempotencyKey = normalizeIdempotencyKey(idempotencyKey);
            return orderDistributedLockService.withCreateOrderLock(
                    userId,
                    monkeyId,
                    () -> transactionOperations.execute(
                            status -> createOrderInTransaction(userId, monkeyId, addressId, normalizedIdempotencyKey)));
        });
    }

    private OrderResponseDto createOrderInTransaction(
            Long userId, Long monkeyId, Long addressId, String normalizedIdempotencyKey) {
        String requestHash = requestHash(monkeyId, addressId);
        OrderIdempotencyService.Reservation reservation =
                orderIdempotencyService.reserve(userId, normalizedIdempotencyKey, requestHash);
        if (!reservation.reserved()) {
            return resolveDuplicateOrder(reservation.record(), requestHash);
        }

        ProductRecord product = orderStore
                .findProductById(monkeyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Product does not exist"));
        AddressRecord address = orderStore
                .findAddressById(addressId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Address does not exist"));
        BuyerRecord buyer = orderStore
                .findBuyerById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "User does not exist"));

        if (!address.userId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Address does not belong to current user");
        }

        if (!product.hasStock()) {
            businessMetricsService.recordStockDeductFailure();
            throw new BusinessException(ErrorCode.OUT_OF_STOCK, "Insufficient stock");
        }
        if (!orderStore.deductProductStock(monkeyId)) {
            businessMetricsService.recordStockDeductFailure();
            throw new BusinessException(ErrorCode.OUT_OF_STOCK, "Insufficient stock");
        }

        OrderRecord order = OrderRecord.place(orderNumberGenerator.nextOrderNo(), buyer, product, address);
        OrderRecord savedOrder = orderStore.savePlacedOrder(order);
        OrderRecord completedOrder = savedOrder != null ? savedOrder : order;
        imageReferenceService.retain(completedOrder.productImage());
        imageReferenceService.retain(completedOrder.buyerAvatar());
        orderIdempotencyService.complete(userId, normalizedIdempotencyKey, completedOrder.id());
        businessMetricsService.recordOrderCreated();
        return OrderDtoAssembler.toResponse(completedOrder);
    }

    @Transactional
    public OrderResponseDto shipOrder(Long orderId) {
        OrderRecord order = requireOrder(orderId);
        return transitionAndMap(order, OrderEvent.SHIP, LocalDateTime.now());
    }

    @Transactional
    public OrderResponseDto receiveOrder(Long orderId, Long userId) {
        OrderRecord order = requireOwnedOrder(orderId, userId);
        return transitionAndMap(order, OrderEvent.RECEIVE, null);
    }

    @Transactional
    public void hideOrderForUser(Long orderId, Long userId) {
        OrderRecord order = requireOwnedOrder(orderId, userId);
        orderStore.hideFromUser(order.id());
    }

    @Transactional
    public OrderResponseDto applyReturn(Long orderId, Long userId) {
        OrderRecord order = requireOwnedOrder(orderId, userId);
        return transitionAndMap(order, OrderEvent.REQUEST_RETURN, null);
    }

    @Transactional
    public OrderResponseDto approveReturn(Long orderId) {
        OrderRecord order = requireOrder(orderId);
        return transitionAndMap(order, OrderEvent.APPROVE_RETURN, null);
    }

    @Transactional
    public OrderResponseDto shipReturn(Long orderId, Long userId) {
        OrderRecord order = requireOwnedOrder(orderId, userId);
        return transitionAndMap(order, OrderEvent.SHIP_RETURN, null);
    }

    @Transactional
    public OrderResponseDto confirmReturn(Long orderId) {
        OrderRecord order = requireOrder(orderId);
        OrderStatus currentStatus = statusOf(order);
        OrderStatus nextStatus = orderTransitionResolver.nextStatus(currentStatus, OrderEvent.REFUND);
        restoreStockForOrder(order);
        return transitionAndMap(order, currentStatus, nextStatus, null);
    }

    private OrderRecord requireOrder(Long orderId) {
        return orderStore
                .findById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Order does not exist"));
    }

    private OrderRecord requireOwnedOrder(Long orderId, Long userId) {
        return orderStore
                .findVisibleByIdAndUserId(orderId, userId)
                .orElseThrow(
                        () -> new BusinessException(ErrorCode.FORBIDDEN, "Order is not available for current user"));
    }

    private void restoreStockForOrder(OrderRecord order) {
        Long productId = order.productId();
        Long orderId = order.id();
        if (productId == null || orderId == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "Product snapshot is missing or stale");
        }
        if (!orderStore.recordStockRestore(orderId, productId)) {
            return;
        }
        if (!orderStore.restoreProductStock(productId)) {
            throw new BusinessException(ErrorCode.CONFLICT, "Product snapshot is missing or stale");
        }
    }

    private OrderResponseDto resolveDuplicateOrder(IdempotencyReservationRecord record, String requestHash) {
        if (record == null) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "Idempotency state is temporarily unavailable");
        }
        if (!requestHash.equals(record.requestHash())) {
            throw new BusinessException(ErrorCode.CONFLICT, "Idempotency-Key was already used for a different request");
        }
        if (record.isCompleted() && record.orderId() != null) {
            OrderRecord order = orderStore
                    .findById(record.orderId())
                    .orElseThrow(() ->
                            new BusinessException(ErrorCode.CONFLICT, "Idempotency record references a missing order"));
            return OrderDtoAssembler.toResponse(order);
        }
        throw new BusinessException(ErrorCode.CONFLICT, "Duplicate order request is already in progress");
    }

    private OrderResponseDto transitionAndMap(OrderRecord order, OrderEvent event, LocalDateTime shippingTime) {
        OrderStatus currentStatus = statusOf(order);
        OrderStatus nextStatus = orderTransitionResolver.nextStatus(currentStatus, event);
        return transitionAndMap(order, currentStatus, nextStatus, shippingTime);
    }

    private OrderResponseDto transitionAndMap(
            OrderRecord order, OrderStatus currentStatus, OrderStatus nextStatus, LocalDateTime shippingTime) {
        int rows = orderStore.transitionStatus(order.id(), currentStatus.label(), nextStatus.label(), shippingTime);
        if (rows == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, OrderTransitionPolicy.STATUS_TRANSITION_NOT_ALLOWED);
        }
        return OrderDtoAssembler.toResponse(order.withStatus(nextStatus, shippingTime));
    }

    private static OrderStatus statusOf(OrderRecord order) {
        try {
            return OrderStatus.fromStoredValue(order.status());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.CONFLICT, OrderTransitionPolicy.STATUS_TRANSITION_NOT_ALLOWED);
        }
    }

    private static PageResponseDto<OrderResponseDto> toPageResponse(OrderPage page) {
        return PageResponseDto.from(
                page.content().stream().map(OrderDtoAssembler::toResponse).toList(),
                page.page(),
                page.size(),
                page.totalElements(),
                page.totalPages(),
                page.first(),
                page.last());
    }

    private static String normalizeIdempotencyKey(String idempotencyKey) {
        if (!StringUtils.hasText(idempotencyKey)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Idempotency-Key header is required");
        }
        String normalized = idempotencyKey.trim();
        if (normalized.length() > MAX_IDEMPOTENCY_KEY_LENGTH
                || !IDEMPOTENCY_KEY_PATTERN.matcher(normalized).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Idempotency-Key header is invalid");
        }
        return normalized;
    }

    private static String requestHash(Long monkeyId, Long addressId) {
        return sha256Hex(monkeyId + ":" + addressId);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest is unavailable", e);
        }
    }
}
