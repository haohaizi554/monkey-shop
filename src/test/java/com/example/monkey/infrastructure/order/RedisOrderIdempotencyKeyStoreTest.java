package com.example.monkey.infrastructure.order;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class RedisOrderIdempotencyKeyStoreTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Test
    void reserveWritesRedisSetNxWindow() {
        RedisOrderIdempotencyKeyStore store = new RedisOrderIdempotencyKeyStore(redisTemplate);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent("order:idempotency:42:order-key-1", "request-hash", Duration.ofHours(24)))
                .thenReturn(true);

        store.reserve(42L, "order-key-1", "request-hash", Duration.ofHours(24));

        verify(valueOperations).setIfAbsent("order:idempotency:42:order-key-1", "request-hash", Duration.ofHours(24));
    }

    @Test
    void reserveIsNoopWhenRedisTemplateIsMissing() {
        RedisOrderIdempotencyKeyStore store = new RedisOrderIdempotencyKeyStore((StringRedisTemplate) null);

        assertThatCode(() -> store.reserve(42L, "order-key-1", "request-hash", Duration.ofHours(24)))
                .doesNotThrowAnyException();
    }

    @Test
    void reserveSwallowsRedisFailuresSoDatabaseCanRemainSourceOfTruth() {
        RedisOrderIdempotencyKeyStore store = new RedisOrderIdempotencyKeyStore(redisTemplate);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        doThrow(new RuntimeException("redis unavailable"))
                .when(valueOperations)
                .setIfAbsent("order:idempotency:42:order-key-1", "request-hash", Duration.ofHours(24));

        assertThatCode(() -> store.reserve(42L, "order-key-1", "request-hash", Duration.ofHours(24)))
                .doesNotThrowAnyException();
    }
}
