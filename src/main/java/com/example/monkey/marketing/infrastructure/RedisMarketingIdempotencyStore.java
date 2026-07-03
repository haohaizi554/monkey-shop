package com.example.monkey.marketing.infrastructure;

import com.example.monkey.marketing.domain.MarketingIdempotencyStore;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisMarketingIdempotencyStore implements MarketingIdempotencyStore {

    private static final Logger log = LoggerFactory.getLogger(RedisMarketingIdempotencyStore.class);
    private static final String REDIS_KEY_PREFIX = "marketing:idempotency:";

    private final StringRedisTemplate redisTemplate;

    public RedisMarketingIdempotencyStore(ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
    }

    @Override
    public boolean reserve(String scope, Long userId, String idempotencyKey, String requestHash, Duration ttl) {
        if (redisTemplate == null) {
            return true;
        }
        try {
            return Boolean.TRUE.equals(redisTemplate
                    .opsForValue()
                    .setIfAbsent(REDIS_KEY_PREFIX + scope + ":" + userId + ":" + idempotencyKey, requestHash, ttl));
        } catch (RuntimeException exception) {
            log.debug("Redis marketing idempotency reservation failed; falling back to database uniqueness", exception);
            return true;
        }
    }
}
