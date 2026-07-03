package com.example.monkey.cart.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.monkey.cart.domain.CartCheckoutStatus;
import com.example.monkey.cart.domain.CartItem;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

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

        store.removeItems(7L, List.of(1001L), Duration.ofDays(7));
        assertThat(store.findCart(7L).items()).isEmpty();
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
    void redissonCartLockManagerRunsDirectlyWithoutClient() {
        ObjectProvider<org.redisson.api.RedissonClient> provider = mock();
        when(provider.getIfAvailable()).thenReturn(null);

        String result = new RedissonCartLockManager(provider).withCheckoutLock(7L, "idem", () -> "ok");

        assertThat(result).isEqualTo("ok");
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
