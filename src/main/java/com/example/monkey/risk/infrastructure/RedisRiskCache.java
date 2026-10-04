package com.example.monkey.risk.infrastructure;

import com.example.monkey.risk.domain.RiskCache;
import com.example.monkey.risk.domain.RiskScore;
import com.example.monkey.shared.application.tenant.TenantContext;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class RedisRiskCache implements RiskCache {

    private static final String STATE_UNAVAILABLE_MESSAGE = "Shared Redis risk state is unavailable";
    static final String DEVICE_USER_PREFIX = "risk:device:";
    static final String SCORE_PREFIX = "risk:score:user:";
    static final String SECKILL_PREFIX = "risk:seckill:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final boolean requireRedisState;
    private final Map<String, Set<String>> fallbackUsersByDevice = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> fallbackPhonesByDevice = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> fallbackSeckillUsers = new ConcurrentHashMap<>();
    private final Map<String, RiskScore> fallbackScores = new ConcurrentHashMap<>();

    @Autowired
    public RedisRiskCache(
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            ObjectMapper objectMapper,
            @Value("${app.risk.require-redis-state:false}") boolean requireRedisState) {
        this(redisTemplateProvider.getIfAvailable(), objectMapper, requireRedisState);
    }

    public RedisRiskCache(ObjectProvider<StringRedisTemplate> redisTemplateProvider, ObjectMapper objectMapper) {
        this(redisTemplateProvider.getIfAvailable(), objectMapper, false);
    }

    RedisRiskCache(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this(redisTemplate, objectMapper, false);
    }

    RedisRiskCache(StringRedisTemplate redisTemplate, ObjectMapper objectMapper, boolean requireRedisState) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.requireRedisState = requireRedisState;
        if (requireRedisState && redisTemplate == null) {
            throw new IllegalStateException(STATE_UNAVAILABLE_MESSAGE);
        }
    }

    @Override
    public void rememberDeviceFingerprint(String deviceFingerprintHash, Long userId, String phoneHmac, Duration ttl) {
        if (!StringUtils.hasText(deviceFingerprintHash) || userId == null) {
            return;
        }
        long tenantId = TenantContext.currentTenantIdOrDefault();
        String usersKey = userKey(tenantId, deviceFingerprintHash);
        String phonesKey = phoneKey(tenantId, deviceFingerprintHash);
        fallbackUsersByDevice
                .computeIfAbsent(usersKey, ignored -> ConcurrentHashMap.newKeySet())
                .add(Long.toString(userId));
        if (StringUtils.hasText(phoneHmac)) {
            fallbackPhonesByDevice
                    .computeIfAbsent(phonesKey, ignored -> ConcurrentHashMap.newKeySet())
                    .add(phoneHmac);
        }
        if (redisTemplate == null) {
            return;
        }
        try {
            redisTemplate.opsForSet().add(usersKey, Long.toString(userId));
            redisTemplate.expire(usersKey, ttl);
            if (StringUtils.hasText(phoneHmac)) {
                redisTemplate.opsForSet().add(phonesKey, phoneHmac);
                redisTemplate.expire(phonesKey, ttl);
            }
        } catch (RuntimeException exception) {
            failIfRedisRequired();
            // The process-local fallback is intentionally development-only.
        }
    }

    @Override
    public long countUsersForDevice(String deviceFingerprintHash) {
        return countSet(
                userKey(TenantContext.currentTenantIdOrDefault(), deviceFingerprintHash), fallbackUsersByDevice);
    }

    @Override
    public long countPhonesForDevice(String deviceFingerprintHash) {
        return countSet(
                phoneKey(TenantContext.currentTenantIdOrDefault(), deviceFingerprintHash), fallbackPhonesByDevice);
    }

    @Override
    public long recordSeckillAttempt(
            Long activityId, Long productId, String deviceFingerprintHash, Long userId, Duration ttl) {
        if (activityId == null || productId == null || !StringUtils.hasText(deviceFingerprintHash) || userId == null) {
            return 0L;
        }
        String key = seckillKey(TenantContext.currentTenantIdOrDefault(), activityId, productId, deviceFingerprintHash);
        fallbackSeckillUsers
                .computeIfAbsent(key, ignored -> ConcurrentHashMap.newKeySet())
                .add(Long.toString(userId));
        if (redisTemplate == null) {
            return fallbackSeckillUsers.getOrDefault(key, Set.of()).size();
        }
        try {
            redisTemplate.opsForSet().add(key, Long.toString(userId));
            redisTemplate.expire(key, ttl);
            Long size = redisTemplate.opsForSet().size(key);
            return size == null
                    ? fallbackSeckillUsers.getOrDefault(key, Set.of()).size()
                    : size;
        } catch (RuntimeException exception) {
            failIfRedisRequired();
            return fallbackSeckillUsers.getOrDefault(key, Set.of()).size();
        }
    }

    @Override
    public void cacheScore(RiskScore score, Duration ttl) {
        if (score == null || score.userId() == null) {
            return;
        }
        String key = scoreKey(TenantContext.currentTenantIdOrDefault(), score.userId());
        fallbackScores.put(key, score);
        if (redisTemplate == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(score), ttl);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Risk score cannot be serialized", exception);
        } catch (RuntimeException exception) {
            failIfRedisRequired();
            // The process-local fallback is intentionally development-only.
        }
    }

    @Override
    public Optional<RiskScore> findScore(Long userId) {
        if (userId == null) {
            return Optional.empty();
        }
        String key = scoreKey(TenantContext.currentTenantIdOrDefault(), userId);
        if (redisTemplate == null) {
            return Optional.ofNullable(fallbackScores.get(key));
        }
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (!StringUtils.hasText(json)) {
                return Optional.ofNullable(fallbackScores.get(key));
            }
            return Optional.of(objectMapper.readValue(json, RiskScore.class));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Risk score cannot be deserialized", exception);
        } catch (RuntimeException exception) {
            failIfRedisRequired();
            return Optional.ofNullable(fallbackScores.get(key));
        }
    }

    private long countSet(String key, Map<String, Set<String>> fallback) {
        if (redisTemplate == null) {
            return fallback.getOrDefault(key, Set.of()).size();
        }
        try {
            Long size = redisTemplate.opsForSet().size(key);
            return size == null ? fallback.getOrDefault(key, Set.of()).size() : size;
        } catch (RuntimeException exception) {
            failIfRedisRequired();
            return fallback.getOrDefault(key, Set.of()).size();
        }
    }

    private void failIfRedisRequired() {
        if (requireRedisState) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, STATE_UNAVAILABLE_MESSAGE);
        }
    }

    private static String userKey(long tenantId, String deviceFingerprintHash) {
        return DEVICE_USER_PREFIX + "tenant:" + tenantId + ":" + deviceFingerprintHash + ":users";
    }

    private static String phoneKey(long tenantId, String deviceFingerprintHash) {
        return DEVICE_USER_PREFIX + "tenant:" + tenantId + ":" + deviceFingerprintHash + ":phones";
    }

    private static String seckillKey(long tenantId, Long activityId, Long productId, String deviceFingerprintHash) {
        return SECKILL_PREFIX + "tenant:" + tenantId + ":" + activityId + ":" + productId + ":device:"
                + deviceFingerprintHash;
    }

    private static String scoreKey(long tenantId, Long userId) {
        return SCORE_PREFIX + tenantId + ":" + userId;
    }
}
