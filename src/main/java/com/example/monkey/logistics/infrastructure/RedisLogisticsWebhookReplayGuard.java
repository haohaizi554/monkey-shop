package com.example.monkey.logistics.infrastructure;

import com.example.monkey.logistics.domain.LogisticsCarrier;
import com.example.monkey.logistics.domain.LogisticsWebhookReplayGuard;
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
@ConditionalOnProperty(name = "app.logistics.webhook-guard", havingValue = "redis", matchIfMissing = true)
public class RedisLogisticsWebhookReplayGuard implements LogisticsWebhookReplayGuard {

    private static final Logger log = LoggerFactory.getLogger(RedisLogisticsWebhookReplayGuard.class);
    private static final String REDIS_KEY_PREFIX = "logistics:webhook:";

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
        if (redisTemplate != null) {
            try {
                Boolean first = redisTemplate
                        .opsForValue()
                        .setIfAbsent(REDIS_KEY_PREFIX + carrier + ":" + eventId, trackingNo, ttl);
                if (!Boolean.TRUE.equals(first)) {
                    return false;
                }
            } catch (RuntimeException exception) {
                log.debug("Redis logistics webhook guard unavailable; falling back to database uniqueness", exception);
            }
        }
        return reserveDatabase(carrier, trackingNo, eventId, sourceIp);
    }

    private boolean reserveDatabase(LogisticsCarrier carrier, String trackingNo, String eventId, String sourceIp) {
        if (webhookLogRepository.findByCarrierAndEventId(carrier, eventId).isPresent()) {
            return false;
        }
        try {
            LogisticsWebhookLogEntity entity = new LogisticsWebhookLogEntity();
            entity.setId(idGenerator.nextId());
            entity.setCarrier(carrier);
            entity.setTrackingNo(trackingNo);
            entity.setEventId(eventId);
            entity.setSourceIp(sourceIp);
            entity.setCreateTime(LocalDateTime.now());
            webhookLogRepository.save(entity);
            return true;
        } catch (DataIntegrityViolationException exception) {
            return false;
        }
    }
}
