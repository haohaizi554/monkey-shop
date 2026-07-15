package com.example.monkey.cart.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.monkey.cart.domain.CartCheckoutStatus;
import com.example.monkey.cart.domain.CartItem;
import com.example.monkey.cart.domain.CartSkuSnapshot;
import com.example.monkey.cart.domain.CartSnapshot;
import com.example.monkey.cart.domain.CheckoutLine;
import com.example.monkey.cart.domain.CheckoutOrder;
import com.example.monkey.cart.domain.CheckoutSubOrder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

class CartInfrastructureTest {

    @Test
    void redisCartStoreFallsBackToLocalHashWhenRedisIsUnavailable() {
        ObjectProvider<StringRedisTemplate> provider = mock();
        when(provider.getIfAvailable()).thenReturn(null);
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        RedisCartStore store = new RedisCartStore(provider, objectMapper);
        LocalDateTime now = LocalDateTime.parse("2026-01-01T00:00:00");

        store.save(new CartSnapshot(7L, List.of(new CartItem(1001L, 1L, 2, true, now, now))), Duration.ofDays(7));
        assertThat(store.findCart(7L).items()).hasSize(1);

        store.removeMatchingItems(7L, List.of(new CartItem(1001L, 1L, 2, true, now, now)), Duration.ofDays(7));
        assertThat(store.findCart(7L).items()).isEmpty();
    }

    @Test
    void cartCleanupRemovesOnlyItemsThatStillExactlyMatchCheckoutSnapshots() {
        ObjectProvider<StringRedisTemplate> provider = mock();
        when(provider.getIfAvailable()).thenReturn(null);
        RedisCartStore store = new RedisCartStore(provider, new ObjectMapper().registerModule(new JavaTimeModule()));
        LocalDateTime checkoutTime = LocalDateTime.parse("2026-01-01T00:00:00");
        LocalDateTime later = checkoutTime.plusMinutes(1);
        CartItem quantityChanged = new CartItem(1001L, 1L, 3, true, checkoutTime, later);
        CartItem unselected = new CartItem(1002L, 1L, 1, false, checkoutTime, later);
        CartItem readded = new CartItem(1003L, 1L, 1, true, later, later);
        CartItem unchanged = new CartItem(1004L, 1L, 1, true, checkoutTime, checkoutTime);
        store.save(new CartSnapshot(7L, List.of(quantityChanged, unselected, readded, unchanged)), Duration.ofDays(7));

        store.removeMatchingItems(
                7L,
                List.of(
                        new CartItem(1001L, 1L, 2, true, checkoutTime, checkoutTime),
                        new CartItem(1002L, 1L, 1, true, checkoutTime, checkoutTime),
                        new CartItem(1003L, 1L, 1, true, checkoutTime, checkoutTime),
                        unchanged),
                Duration.ofDays(7));

        assertThat(store.findCart(7L).items()).containsExactlyInAnyOrder(quantityChanged, unselected, readded);
        store.removeMatchingItems(7L, List.of(unchanged), Duration.ofDays(7));
        assertThat(store.findCart(7L).items()).containsExactlyInAnyOrder(quantityChanged, unselected, readded);
    }

    @Test
    void redisCleanupUsesOneLuaScriptForCompareDeleteAndTtlRefresh() {
        ObjectProvider<StringRedisTemplate> provider = mock();
        StringRedisTemplate redisTemplate = mock();
        HashOperations<String, Object, Object> hashOperations = mock();
        when(provider.getIfAvailable()).thenReturn(redisTemplate);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        RedisCartStore store = new RedisCartStore(provider, new ObjectMapper().registerModule(new JavaTimeModule()));
        LocalDateTime now = LocalDateTime.parse("2026-01-01T00:00:00");

        store.removeMatchingItems(7L, List.of(new CartItem(1001L, 1L, 2, true, now, now)), Duration.ofDays(7));

        var scriptCaptor = org.mockito.ArgumentCaptor.forClass(DefaultRedisScript.class);
        verify(redisTemplate, times(1))
                .execute(scriptCaptor.capture(), eq(List.of("cart:user:7")), any(Object[].class));
        assertThat(scriptCaptor.getValue().getScriptAsString()).contains("HGET", "HDEL", "EXPIRE");
        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
        verifyNoInteractions(hashOperations);
    }

    @Test
    void jpaCartCheckoutStoreMapsMasterSubOrderAndLines() {
        CartCheckoutRepository checkoutRepository = mock();
        CartSubOrderRepository subOrderRepository = mock();
        CartCheckoutLineRepository lineRepository = mock();
        LocalDateTime now = LocalDateTime.parse("2026-01-01T00:00:00");
        CheckoutOrder checkout = checkout(now);
        when(checkoutRepository.save(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(checkoutRepository.findByUserIdAndIdempotencyKey(7L, "idem")).thenReturn(Optional.of(checkoutEntity(now)));
        when(subOrderRepository.findByCheckoutIdOrderByIdAsc(1L)).thenReturn(List.of(subOrderEntity(now)));
        when(lineRepository.findByCheckoutIdOrderBySubOrderIdAscIdAsc(1L)).thenReturn(List.of(lineEntity(now)));
        JpaCartCheckoutStore store = new JpaCartCheckoutStore(checkoutRepository, subOrderRepository, lineRepository);

        assertThat(store.save(checkout).id()).isEqualTo(1L);
        CheckoutOrder restored = store.findByUserIdAndIdempotencyKey(7L, "idem").orElseThrow();
        assertThat(restored.subOrders()).hasSize(1);
        assertThat(restored.subOrders().get(0).lines()).hasSize(1);
        assertThat(restored.payableAmount()).isEqualByComparingTo("90.00");
    }

    @Test
    void jpaCartCatalogReaderReadsSkuSnapshotThroughTenantScopedJdbcQuery() {
        JdbcTemplate jdbcTemplate = mock();
        CartSkuSnapshot snapshot =
                new CartSkuSnapshot(1001L, 2001L, 11L, "SKU-1", "Phone", "/phone.png", new BigDecimal("88.00"));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(1001L), eq(1L), eq("LISTED")))
                .thenReturn(List.of(snapshot));
        JpaCartCatalogReader reader = new JpaCartCatalogReader(jdbcTemplate);

        Optional<CartSkuSnapshot> result = reader.findActiveSku(1001L);

        assertThat(result).contains(snapshot);
        verify(jdbcTemplate).query(anyString(), any(RowMapper.class), eq(1001L), eq(1L), eq("LISTED"));
    }

    @Test
    void redissonCartLockManagerRunsDirectlyWithoutClient() {
        ObjectProvider<org.redisson.api.RedissonClient> provider = mock();
        when(provider.getIfAvailable()).thenReturn(null);

        String result = new RedissonCartLockManager(provider).withCheckoutLock(7L, "idem", () -> "ok");

        assertThat(result).isEqualTo("ok");
    }

    @Test
    void redissonCartLockUsesWatchdogInsteadOfFixedLease() throws InterruptedException {
        ObjectProvider<RedissonClient> provider = mock();
        RedissonClient client = mock();
        RLock lock = mock();
        when(provider.getIfAvailable()).thenReturn(client);
        when(client.getLock("cart:checkout:7:idem")).thenReturn(lock);
        when(lock.tryLock(2000L, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        String result = new RedissonCartLockManager(provider).withCheckoutLock(7L, "idem", () -> "ok");

        assertThat(result).isEqualTo("ok");
        verify(lock).tryLock(2000L, TimeUnit.MILLISECONDS);
        verify(lock, never()).tryLock(anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS));
        verify(lock).unlock();
    }

    @Test
    void cartTransactionRunnerCommitsRequiresNewBeforeReturning() {
        PlatformTransactionManager transactionManager = mock();
        SimpleTransactionStatus status = new SimpleTransactionStatus();
        when(transactionManager.getTransaction(any())).thenReturn(status);
        RequiresNewCartTransactions transactions = new RequiresNewCartTransactions(transactionManager);

        String result = transactions.execute(() -> "committed");

        var definition = org.mockito.ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager).getTransaction(definition.capture());
        verify(transactionManager).commit(status);
        assertThat(definition.getValue().getPropagationBehavior())
                .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThat(result).isEqualTo("committed");
    }

    private static CheckoutOrder checkout(LocalDateTime now) {
        CheckoutLine line = new CheckoutLine(
                3L,
                1001L,
                1L,
                11L,
                "Phone",
                "/phone.png",
                1,
                new BigDecimal("100.00"),
                new BigDecimal("100.00"),
                new BigDecimal("10.00"),
                new BigDecimal("90.00"),
                List.of("SHOP-10"),
                "cart:idem:1001",
                9001L);
        return new CheckoutOrder(
                1L,
                "ORD1",
                7L,
                9L,
                "idem",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                new BigDecimal("100.00"),
                new BigDecimal("10.00"),
                new BigDecimal("90.00"),
                CartCheckoutStatus.CHECKED_OUT,
                "CN-BJ",
                now,
                List.of(new CheckoutSubOrder(
                        2L,
                        1L,
                        "ORD2",
                        new BigDecimal("100.00"),
                        new BigDecimal("10.00"),
                        new BigDecimal("90.00"),
                        CartCheckoutStatus.CHECKED_OUT,
                        List.of(line))));
    }

    private static CartCheckoutEntity checkoutEntity(LocalDateTime now) {
        CartCheckoutEntity entity = new CartCheckoutEntity();
        entity.setId(1L);
        entity.setCheckoutNo("ORD1");
        entity.setUserId(7L);
        entity.setAddressId(9L);
        entity.setIdempotencyKey("idem");
        entity.setRequestFingerprint("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        entity.setOriginalAmount(new BigDecimal("100.00"));
        entity.setDiscountAmount(new BigDecimal("10.00"));
        entity.setPayableAmount(new BigDecimal("90.00"));
        entity.setStatus(CartCheckoutStatus.CHECKED_OUT);
        entity.setProvince("CN-BJ");
        entity.setCreateTime(now);
        return entity;
    }

    private static CartSubOrderEntity subOrderEntity(LocalDateTime now) {
        CartSubOrderEntity entity = new CartSubOrderEntity();
        entity.setId(2L);
        entity.setCheckoutId(1L);
        entity.setOrderNo("ORD2");
        entity.setShopId(1L);
        entity.setOriginalAmount(new BigDecimal("100.00"));
        entity.setStoreDiscountAmount(new BigDecimal("10.00"));
        entity.setPlatformDiscountAmount(BigDecimal.ZERO);
        entity.setDiscountAmount(new BigDecimal("10.00"));
        entity.setPayableAmount(new BigDecimal("90.00"));
        entity.setStatus(CartCheckoutStatus.CHECKED_OUT);
        entity.setCreateTime(now);
        return entity;
    }

    private static CartCheckoutLineEntity lineEntity(LocalDateTime now) {
        CartCheckoutLineEntity entity = new CartCheckoutLineEntity();
        entity.setId(3L);
        entity.setCheckoutId(1L);
        entity.setSubOrderId(2L);
        entity.setSkuId(1001L);
        entity.setShopId(1L);
        entity.setCategoryId(11L);
        entity.setProductName("Phone");
        entity.setProductImage("/phone.png");
        entity.setQuantity(1);
        entity.setUnitPrice(new BigDecimal("100.00"));
        entity.setOriginalAmount(new BigDecimal("100.00"));
        entity.setDiscountAmount(new BigDecimal("10.00"));
        entity.setPayableAmount(new BigDecimal("90.00"));
        entity.setCouponCodes("SHOP-10");
        entity.setReservationKey("cart:idem:1001");
        entity.setWarehouseId(9001L);
        entity.setCreateTime(now);
        return entity;
    }
}
