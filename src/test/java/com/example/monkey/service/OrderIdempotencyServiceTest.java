package com.example.monkey.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.domain.order.OrderIdempotencyStore;
import com.example.monkey.domain.order.OrderIdempotencyStore.IdempotencyReservationRecord;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class OrderIdempotencyServiceTest {

    @Mock
    private OrderIdempotencyStore orderIdempotencyStore;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> redisValues;

    @Test
    void reserveWritesRedisSetNxAndDatabaseRecord() {
        Clock clock = Clock.fixed(Instant.parse("2026-06-28T00:00:00Z"), ZoneOffset.UTC);
        OrderIdempotencyService service =
                new OrderIdempotencyService(orderIdempotencyStore, redisTemplate, Duration.ofHours(24), clock);
        when(redisTemplate.opsForValue()).thenReturn(redisValues);
        when(redisValues.setIfAbsent("order:idempotency:42:order-key-1", "request-hash", Duration.ofHours(24)))
                .thenReturn(true);
        when(orderIdempotencyStore.reserve(42L, "order-key-1", "request-hash", LocalDateTime.of(2026, 6, 29, 0, 0)))
                .thenReturn(true);

        OrderIdempotencyService.Reservation reservation = service.reserve(42L, "order-key-1", "request-hash");

        assertThat(reservation.reserved()).isTrue();
        verify(redisValues).setIfAbsent("order:idempotency:42:order-key-1", "request-hash", Duration.ofHours(24));
        verify(orderIdempotencyStore).reserve(42L, "order-key-1", "request-hash", LocalDateTime.of(2026, 6, 29, 0, 0));
    }

    @Test
    void reserveReturnsExistingRecordWhenDatabaseUniqueKeyAlreadyExists() {
        IdempotencyReservationRecord record =
                idempotencyRecord("request-hash", IdempotencyReservationRecord.STATUS_COMPLETED, 11L);
        when(orderIdempotencyStore.reserve(eq(42L), eq("order-key-1"), eq("request-hash"), any(LocalDateTime.class)))
                .thenReturn(false);
        when(orderIdempotencyStore.find(42L, "order-key-1")).thenReturn(Optional.of(record));
        OrderIdempotencyService service =
                new OrderIdempotencyService(orderIdempotencyStore, (StringRedisTemplate) null, Duration.ofHours(24));

        OrderIdempotencyService.Reservation reservation = service.reserve(42L, "order-key-1", "request-hash");

        assertThat(reservation.reserved()).isFalse();
        assertThat(reservation.record()).isSameAs(record);
    }

    @Test
    void reserveFallsBackToDatabaseWhenRedisWriteFails() {
        OrderIdempotencyService service =
                new OrderIdempotencyService(orderIdempotencyStore, redisTemplate, Duration.ofHours(24));
        when(redisTemplate.opsForValue()).thenReturn(redisValues);
        doThrow(new RuntimeException("redis unavailable"))
                .when(redisValues)
                .setIfAbsent("order:idempotency:42:order-key-1", "request-hash", Duration.ofHours(24));
        when(orderIdempotencyStore.reserve(eq(42L), eq("order-key-1"), eq("request-hash"), any(LocalDateTime.class)))
                .thenReturn(true);

        OrderIdempotencyService.Reservation reservation = service.reserve(42L, "order-key-1", "request-hash");

        assertThat(reservation.reserved()).isTrue();
    }

    @Test
    void completeStoresCreatedOrderId() {
        OrderIdempotencyService service =
                new OrderIdempotencyService(orderIdempotencyStore, (StringRedisTemplate) null, Duration.ofHours(24));

        service.complete(42L, "order-key-1", 11L);

        verify(orderIdempotencyStore).complete(42L, "order-key-1", 11L);
    }

    private static IdempotencyReservationRecord idempotencyRecord(String requestHash, String status, Long orderId) {
        return new IdempotencyReservationRecord(
                1L,
                42L,
                "order-key-1",
                requestHash,
                orderId,
                status,
                LocalDateTime.parse("2026-06-28T00:00:00"),
                LocalDateTime.parse("2026-06-29T00:00:00"));
    }
}
