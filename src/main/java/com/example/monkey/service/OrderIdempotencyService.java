package com.example.monkey.service;

import com.example.monkey.domain.order.OrderIdempotencyKeyStore;
import com.example.monkey.domain.order.OrderIdempotencyStore;
import com.example.monkey.domain.order.OrderIdempotencyStore.IdempotencyReservationRecord;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class OrderIdempotencyService {

    private static final Duration DEFAULT_TTL = Duration.ofHours(24);

    private final OrderIdempotencyKeyStore orderIdempotencyKeyStore;
    private final OrderIdempotencyStore orderIdempotencyStore;
    private final Duration ttl;
    private final Clock clock;

    public OrderIdempotencyService(
            OrderIdempotencyKeyStore orderIdempotencyKeyStore,
            OrderIdempotencyStore orderIdempotencyStore,
            @Value("${app.order.idempotency.ttl:PT24H}") Duration ttl) {
        this(orderIdempotencyKeyStore, orderIdempotencyStore, ttl, Clock.systemUTC());
    }

    OrderIdempotencyService(
            OrderIdempotencyKeyStore orderIdempotencyKeyStore,
            OrderIdempotencyStore orderIdempotencyStore,
            Duration ttl,
            Clock clock) {
        this.orderIdempotencyKeyStore = orderIdempotencyKeyStore;
        this.orderIdempotencyStore = orderIdempotencyStore;
        this.ttl = ttl == null || ttl.isNegative() || ttl.isZero() ? DEFAULT_TTL : ttl;
        this.clock = clock;
    }

    public Reservation reserve(Long userId, String idempotencyKey, String requestHash) {
        orderIdempotencyKeyStore.reserve(userId, idempotencyKey, requestHash, ttl);
        boolean reserved = orderIdempotencyStore.reserve(
                userId, idempotencyKey, requestHash, LocalDateTime.now(clock).plus(ttl));
        if (reserved) {
            return Reservation.newReservation();
        }
        IdempotencyReservationRecord record =
                orderIdempotencyStore.find(userId, idempotencyKey).orElse(null);
        return Reservation.duplicate(record);
    }

    public void complete(Long userId, String idempotencyKey, Long orderId) {
        if (orderId == null) {
            return;
        }
        orderIdempotencyStore.complete(userId, idempotencyKey, orderId);
    }

    public record Reservation(boolean reserved, IdempotencyReservationRecord record) {
        static Reservation newReservation() {
            return new Reservation(true, null);
        }

        static Reservation duplicate(IdempotencyReservationRecord record) {
            return new Reservation(false, record);
        }
    }
}
