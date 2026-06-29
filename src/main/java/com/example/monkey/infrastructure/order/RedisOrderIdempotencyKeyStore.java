package com.example.monkey.infrastructure.order;

import com.example.monkey.domain.order.OrderIdempotencyKeyStore;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisOrderIdempotencyKeyStore implements OrderIdempotencyKeyStore {

    private static final Logger log = LoggerFactory.getLogger(RedisOrderIdempotencyKeyStore.class);
    private static final String REDIS_KEY_PREFIX = "order:idempotency:";

    private final StringRedisTemplate redisTemplate;

    public RedisOrderIdempotencyKeyStore(ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        this(redisTemplateProvider.getIfAvailable());
    }

    RedisOrderIdempotencyKeyStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void reserve(Long userId, String idempotencyKey, String requestHash, Duration ttl) {
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
}
