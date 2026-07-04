package com.example.monkey.payment.infrastructure;

import com.example.monkey.payment.domain.PaymentCallbackReplayGuard;
import com.example.monkey.payment.domain.PaymentMethod;
import com.example.monkey.shared.domain.id.IdGenerator;
import java.time.Duration;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.payment.callback-guard", havingValue = "redis", matchIfMissing = true)
public class RedisPaymentCallbackReplayGuard implements PaymentCallbackReplayGuard {

    private static final Logger log = LoggerFactory.getLogger(RedisPaymentCallbackReplayGuard.class);
    private static final String REDIS_KEY_PREFIX = "payment:callback:";

    private final StringRedisTemplate redisTemplate;
    private final PaymentCallbackLogRepository callbackLogRepository;
    private final IdGenerator idGenerator;

    public RedisPaymentCallbackReplayGuard(
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            PaymentCallbackLogRepository callbackLogRepository,
            IdGenerator idGenerator) {
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
        this.callbackLogRepository = callbackLogRepository;
        this.idGenerator = idGenerator;
    }

    @Override
    public boolean reserve(PaymentMethod provider, String paymentNo, String callbackId, Duration ttl) {
        if (redisTemplate != null) {
            try {
                Boolean first = redisTemplate
                        .opsForValue()
                        .setIfAbsent(REDIS_KEY_PREFIX + provider + ":" + callbackId, paymentNo, ttl);
                if (!Boolean.TRUE.equals(first)) {
                    return false;
                }
            } catch (RuntimeException exception) {
                log.debug("Redis payment callback guard unavailable; falling back to database uniqueness", exception);
            }
        }
        return reserveDatabase(provider, paymentNo, callbackId);
    }

    private boolean reserveDatabase(PaymentMethod provider, String paymentNo, String callbackId) {
        if (callbackLogRepository
                .findByProviderAndCallbackId(provider, callbackId)
                .isPresent()) {
            return false;
        }
        try {
            PaymentCallbackLogEntity entity = new PaymentCallbackLogEntity();
            entity.setId(idGenerator.nextId());
            entity.setProvider(provider);
            entity.setPaymentNo(paymentNo);
            entity.setCallbackId(callbackId);
            entity.setCreateTime(LocalDateTime.now());
            callbackLogRepository.save(entity);
            return true;
        } catch (DataIntegrityViolationException exception) {
            return false;
        }
    }
}
