package com.example.monkey.service;

import com.example.monkey.domain.order.OrderIdempotencyStore;
import com.example.monkey.domain.order.OrderIdempotencyStore.IdempotencyReservationRecord;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class OrderIdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(OrderIdempotencyService.class);
    private static final String REDIS_KEY_PREFIX = "order:idempotency:";
    private static final Duration DEFAULT_TTL = Duration.ofHours(24);

    private final OrderIdempotencyStore orderIdempotencyStore;
    private final StringRedisTemplate redisTemplate;
    private final Duration ttl;
    private final Clock clock;

    public OrderIdempotencyService(
            OrderIdempotencyStore orderIdempotencyStore,
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            @Value("${app.order.idempotency.ttl:PT24H}") Duration ttl) {
        this(orderIdempotencyStore, redisTemplateProvider.getIfAvailable(), ttl, Clock.systemUTC());
    }

    OrderIdempotencyService(
            OrderIdempotencyStore orderIdempotencyStore, StringRedisTemplate redisTemplate, Duration ttl) {
        this(orderIdempotencyStore, redisTemplate, ttl, Clock.systemUTC());
    }

    OrderIdempotencyService(
            OrderIdempotencyStore orderIdempotencyStore, StringRedisTemplate redisTemplate, Duration ttl, Clock clock) {
        this.orderIdempotencyStore = orderIdempotencyStore;
        this.redisTemplate = redisTemplate;
        this.ttl = ttl == null || ttl.isNegative() || ttl.isZero() ? DEFAULT_TTL : ttl;
        this.clock = clock;
    }

    public Reservation reserve(Long userId, String idempotencyKey, String requestHash) {
        reserveRedisKey(userId, idempotencyKey, requestHash);
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

    private void reserveRedisKey(Long userId, String idempotencyKey, String requestHash) {
        if (redisTemplate == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().setIfAbsent(redisKey(userId, idempotencyKey), requestHash, ttl);
        } catch (RuntimeException e) {
            log.debug("Redis idempotency reservation failed; falling back to database", e);
        }
    }

    private static String redisKey(Long userId, String idempotencyKey) {
        return REDIS_KEY_PREFIX + userId + ":" + idempotencyKey;
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
