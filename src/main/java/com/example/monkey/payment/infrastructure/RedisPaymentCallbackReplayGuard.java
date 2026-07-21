package com.example.monkey.payment.infrastructure;

import com.example.monkey.payment.domain.PaymentCallbackReplayGuard;
import com.example.monkey.payment.domain.PaymentMethod;
import com.example.monkey.shared.application.tenant.TenantContext;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.shared.domain.id.IdGenerator;
import java.time.Duration;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
@ConditionalOnProperty(name = "app.payment.callback-guard", havingValue = "redis", matchIfMissing = true)
public class RedisPaymentCallbackReplayGuard implements PaymentCallbackReplayGuard {

    private static final Logger log = LoggerFactory.getLogger(RedisPaymentCallbackReplayGuard.class);
    private static final String REDIS_KEY_PREFIX = "payment:callback:v2:";

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
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(paymentNo, "paymentNo");
        Objects.requireNonNull(callbackId, "callbackId");
        Duration markerTtl = requirePositiveTtl(ttl);
        long tenantId = TenantContext.currentTenantIdOrDefault();
        int inserted =
                callbackLogRepository.reserve(tenantId, idGenerator.nextId(), provider.name(), paymentNo, callbackId);
        if (inserted == 1) {
            publishAfterCommit(tenantId, provider, paymentNo, callbackId, markerTtl);
            return true;
        }

        PaymentCallbackLogEntity existing = callbackLogRepository
                .findByTenantIdAndProviderAndCallbackId(tenantId, provider, callbackId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.SERVICE_UNAVAILABLE, "Payment callback reservation could not be verified"));
        if (paymentNo.equals(existing.getPaymentNo())) {
            return false;
        }
        throw new BusinessException(ErrorCode.CONFLICT, "Payment callback id is already bound to another payment");
    }

    private void publishAfterCommit(
            long tenantId,
            PaymentMethod provider,
            String paymentNo,
            String callbackId,
            Duration ttl) {
        if (redisTemplate == null
                || !TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        String key = REDIS_KEY_PREFIX + tenantId + ":" + provider + ":" + callbackId;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    redisTemplate.opsForValue().set(key, paymentNo, ttl);
                } catch (RuntimeException exception) {
                    log.warn("Could not publish committed payment callback marker", exception);
                }
            }
        });
    }

    private static Duration requirePositiveTtl(Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("Payment callback marker TTL must be positive");
        }
        return ttl;
    }
}
