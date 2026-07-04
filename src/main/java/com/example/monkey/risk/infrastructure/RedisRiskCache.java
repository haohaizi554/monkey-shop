package com.example.monkey.risk.infrastructure;

import com.example.monkey.risk.domain.RiskCache;
import com.example.monkey.risk.domain.RiskScore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class RedisRiskCache implements RiskCache {

    static final String DEVICE_USER_PREFIX = "risk:device:";
    static final String SCORE_PREFIX = "risk:score:user:";
    static final String SECKILL_PREFIX = "risk:seckill:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Map<String, Set<String>> fallbackUsersByDevice = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> fallbackPhonesByDevice = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> fallbackSeckillUsers = new ConcurrentHashMap<>();
    private final Map<Long, RiskScore> fallbackScores = new ConcurrentHashMap<>();

    public RedisRiskCache(ObjectProvider<StringRedisTemplate> redisTemplateProvider, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
        this.objectMapper = objectMapper;
    }

    RedisRiskCache(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void rememberDeviceFingerprint(String deviceFingerprintHash, Long userId, String phoneHmac, Duration ttl) {
        if (!StringUtils.hasText(deviceFingerprintHash) || userId == null) {
            return;
        }
        fallbackUsersByDevice
                .computeIfAbsent(userKey(deviceFingerprintHash), ignored -> ConcurrentHashMap.newKeySet())
                .add(Long.toString(userId));
        if (StringUtils.hasText(phoneHmac)) {
            fallbackPhonesByDevice
                    .computeIfAbsent(phoneKey(deviceFingerprintHash), ignored -> ConcurrentHashMap.newKeySet())
                    .add(phoneHmac);
        }
        if (redisTemplate == null) {
            return;
        }
        try {
            redisTemplate.opsForSet().add(userKey(deviceFingerprintHash), Long.toString(userId));
            redisTemplate.expire(userKey(deviceFingerprintHash), ttl);
            if (StringUtils.hasText(phoneHmac)) {
                redisTemplate.opsForSet().add(phoneKey(deviceFingerprintHash), phoneHmac);
                redisTemplate.expire(phoneKey(deviceFingerprintHash), ttl);
            }
        } catch (RuntimeException ignored) {
            // Fallback sets already contain the signal.
        }
    }

    @Override
    public long countUsersForDevice(String deviceFingerprintHash) {
        return countSet(userKey(deviceFingerprintHash), fallbackUsersByDevice);
    }

    @Override
    public long countPhonesForDevice(String deviceFingerprintHash) {
        return countSet(phoneKey(deviceFingerprintHash), fallbackPhonesByDevice);
    }

    @Override
    public long recordSeckillAttempt(
            Long activityId, Long productId, String deviceFingerprintHash, Long userId, Duration ttl) {
        if (activityId == null || productId == null || !StringUtils.hasText(deviceFingerprintHash) || userId == null) {
            return 0L;
        }
        String key = seckillKey(activityId, productId, deviceFingerprintHash);
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
        } catch (RuntimeException ignored) {
            return fallbackSeckillUsers.getOrDefault(key, Set.of()).size();
        }
    }

    @Override
    public void cacheScore(RiskScore score, Duration ttl) {
        if (score == null || score.userId() == null) {
            return;
        }
        fallbackScores.put(score.userId(), score);
        if (redisTemplate == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(scoreKey(score.userId()), objectMapper.writeValueAsString(score), ttl);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Risk score cannot be serialized", exception);
        } catch (RuntimeException ignored) {
            // Fallback cache already contains the score.
        }
    }

    @Override
    public Optional<RiskScore> findScore(Long userId) {
        if (userId == null) {
            return Optional.empty();
        }
        if (redisTemplate == null) {
            return Optional.ofNullable(fallbackScores.get(userId));
        }
        try {
            String json = redisTemplate.opsForValue().get(scoreKey(userId));
            if (!StringUtils.hasText(json)) {
                return Optional.ofNullable(fallbackScores.get(userId));
            }
            return Optional.of(objectMapper.readValue(json, RiskScore.class));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Risk score cannot be deserialized", exception);
        } catch (RuntimeException ignored) {
            return Optional.ofNullable(fallbackScores.get(userId));
        }
    }

    private long countSet(String key, Map<String, Set<String>> fallback) {
        if (redisTemplate == null) {
            return fallback.getOrDefault(key, Set.of()).size();
        }
        try {
            Long size = redisTemplate.opsForSet().size(key);
            return size == null ? fallback.getOrDefault(key, Set.of()).size() : size;
        } catch (RuntimeException ignored) {
            return fallback.getOrDefault(key, Set.of()).size();
        }
    }

    private static String userKey(String deviceFingerprintHash) {
        return DEVICE_USER_PREFIX + deviceFingerprintHash + ":users";
    }

    private static String phoneKey(String deviceFingerprintHash) {
        return DEVICE_USER_PREFIX + deviceFingerprintHash + ":phones";
    }

    private static String seckillKey(Long activityId, Long productId, String deviceFingerprintHash) {
        return SECKILL_PREFIX + activityId + ":" + productId + ":device:" + deviceFingerprintHash;
    }

    private static String scoreKey(Long userId) {
        return SCORE_PREFIX + userId;
    }
}
