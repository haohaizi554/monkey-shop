package com.example.monkey.logistics.infrastructure;

import com.example.monkey.logistics.domain.LogisticsCarrier;
import com.example.monkey.logistics.domain.LogisticsWebhookReplayGuard;
import com.example.monkey.shared.application.tenant.TenantContext;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.shared.domain.id.IdGenerator;
import java.time.Duration;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@ConditionalOnProperty(name = "app.logistics.webhook-guard", havingValue = "redis", matchIfMissing = true)
public class RedisLogisticsWebhookReplayGuard implements LogisticsWebhookReplayGuard {

    private static final Logger log = LoggerFactory.getLogger(RedisLogisticsWebhookReplayGuard.class);
    private static final String REDIS_KEY_PREFIX = "logistics:webhook:v2:";

    private final StringRedisTemplate redisTemplate;
    private final LogisticsWebhookLogRepository webhookLogRepository;
    private final IdGenerator idGenerator;

    public RedisLogisticsWebhookReplayGuard(
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            LogisticsWebhookLogRepository webhookLogRepository,
            IdGenerator idGenerator) {
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
        this.webhookLogRepository = webhookLogRepository;
        this.idGenerator = idGenerator;
    }

    @Override
    public boolean reserve(LogisticsCarrier carrier, String trackingNo, String eventId, Duration ttl, String sourceIp) {
        Objects.requireNonNull(carrier, "carrier");
        Objects.requireNonNull(trackingNo, "trackingNo");
        Objects.requireNonNull(eventId, "eventId");
        Duration markerTtl = requirePositiveTtl(ttl);
        long tenantId = TenantContext.currentTenantIdOrDefault();
        int inserted = webhookLogRepository.reserve(
                tenantId, idGenerator.nextId(), carrier.name(), trackingNo, eventId, sourceIp);
        if (inserted == 1) {
            publishAfterCommit(tenantId, carrier, trackingNo, eventId, markerTtl);
            return true;
        }

        LogisticsWebhookLogEntity existing = webhookLogRepository
                .findByTenantIdAndCarrierAndEventId(tenantId, carrier, eventId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.SERVICE_UNAVAILABLE, "Logistics webhook reservation could not be verified"));
        if (trackingNo.equals(existing.getTrackingNo())) {
            return false;
        }
        throw new BusinessException(ErrorCode.CONFLICT, "Logistics webhook event is already bound to another tracking");
    }

    private void publishAfterCommit(
            long tenantId, LogisticsCarrier carrier, String trackingNo, String eventId, Duration ttl) {
        if (redisTemplate == null
                || !TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        String key = REDIS_KEY_PREFIX + tenantId + ":" + carrier + ":" + eventId;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    redisTemplate.opsForValue().set(key, trackingNo, ttl);
                } catch (RuntimeException exception) {
                    log.warn("Could not publish committed logistics webhook marker", exception);
                }
            }
        });
    }

    private static Duration requirePositiveTtl(Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("Logistics webhook marker TTL must be positive");
        }
        return ttl;
    }
}
