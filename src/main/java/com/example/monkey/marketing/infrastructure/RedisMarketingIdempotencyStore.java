package com.example.monkey.marketing.infrastructure;

import com.example.monkey.marketing.domain.MarketingIdempotencyStore;
import com.example.monkey.shared.application.tenant.TenantContext;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return true;
        }
        String key = redisKey(scope, userId, idempotencyKey);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    redisTemplate.opsForValue().setIfAbsent(key, requestHash, ttl);
                } catch (RuntimeException exception) {
                    log.debug("Could not publish committed marketing idempotency marker", exception);
                }
            }
        });
        // Redis is an optimization only. The durable marketing row and its
        // tenant-scoped uniqueness constraint decide whether a request exists.
        return true;
    }

    @Override
    public Optional<String> find(String scope, Long userId, String idempotencyKey) {
        if (redisTemplate == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(redisTemplate.opsForValue().get(redisKey(scope, userId, idempotencyKey)));
        } catch (RuntimeException exception) {
            log.debug("Could not read marketing idempotency marker", exception);
            return Optional.empty();
        }
    }

    private static String redisKey(String scope, Long userId, String idempotencyKey) {
        return REDIS_KEY_PREFIX + "tenant:" + TenantContext.currentTenantIdOrDefault() + ":" + scope + ":"
                + userId + ":" + idempotencyKey;
    }
}
