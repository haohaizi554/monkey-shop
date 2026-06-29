package com.example.monkey.security;

import com.example.monkey.domain.security.ApiRateLimiter;
import com.example.monkey.domain.security.ApiRateLimiter.RateLimitDecision;
import com.example.monkey.domain.security.RateLimitPolicy;
import com.example.monkey.shared.api.ErrorCode;
import com.example.monkey.shared.exception.BusinessException;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.Refill;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ApiRateLimitService implements ApiRateLimiter {

    static final String STATE_UNAVAILABLE_MESSAGE = "api rate limit state store unavailable";
    private static final String REDIS_LIMIT_PREFIX = "api:rate:";
    private static final String REDIS_BLOCK_PREFIX = "api:waf:block:";
    private static final Duration DEFAULT_HONEYPOT_BLOCK = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;
    private final boolean enabled;
    private final boolean requireRedisState;
    private final Duration honeypotBlockDuration;
    private final Map<String, Bucket> localBuckets = new ConcurrentHashMap<>();
    private final Map<String, Long> localBlocks = new ConcurrentHashMap<>();

    public ApiRateLimitService(
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            @Value("${app.rate-limit.enabled:true}") boolean enabled,
            @Value("${app.rate-limit.require-redis-state:${app.auth.require-redis-state:false}}")
                    boolean requireRedisState,
            @Value("${app.rate-limit.honeypot.block-seconds:86400}") long honeypotBlockSeconds) {
        this(
                redisTemplateProvider.getIfAvailable(),
                enabled,
                requireRedisState,
                Duration.ofSeconds(honeypotBlockSeconds));
    }

    ApiRateLimitService(
            StringRedisTemplate redisTemplate,
            boolean enabled,
            boolean requireRedisState,
            Duration honeypotBlockDuration) {
        this.redisTemplate = redisTemplate;
        this.enabled = enabled;
        this.requireRedisState = requireRedisState;
        if (enabled && requireRedisState && redisTemplate == null) {
            throw new IllegalStateException(STATE_UNAVAILABLE_MESSAGE);
        }
        this.honeypotBlockDuration = positiveDuration(honeypotBlockDuration, DEFAULT_HONEYPOT_BLOCK);
    }

    @Override
    public RateLimitDecision consume(RateLimitPolicy policy, String clientIp, String userKey) {
        if (!enabled || policy == null) {
            return RateLimitDecision.allowedDecision();
        }
        RateLimitDecision ipDecision = consumeDimension(policy, "ip", clientIp);
        if (!ipDecision.allowed()) {
            return ipDecision;
        }
        RateLimitDecision userDecision = consumeDimension(policy, "user", userKey);
        if (!userDecision.allowed()) {
            return userDecision;
        }
        return consumeDimension(policy, "endpoint", policy.key());
    }

    @Override
    public boolean isBlocked(String clientIp) {
        String key = blockKey(clientIp);
        if (requireRedisState) {
            return hasRedisKey(key);
        }
        Long expiresAt = localBlocks.get(key);
        long now = System.currentTimeMillis();
        if (expiresAt != null && expiresAt > now) {
            return true;
        }
        if (expiresAt != null) {
            localBlocks.remove(key);
        }
        return hasRedisKey(key);
    }

    @Override
    public void blockForHoneypot(String clientIp) {
        String key = blockKey(clientIp);
        if (!requireRedisState) {
            localBlocks.put(key, System.currentTimeMillis() + honeypotBlockDuration.toMillis());
        }
        if (redisTemplate == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(key, "1", honeypotBlockDuration);
        } catch (Exception exception) {
            failIfRedisRequired();
        }
    }

    private RateLimitDecision consumeDimension(RateLimitPolicy policy, String dimension, String rawValue) {
        String key = rateLimitKey(policy, dimension, rawValue);
        Optional<Long> redisCount = incrementRedisCounter(key, policy.window());
        if (redisCount.isPresent()) {
            long count = redisCount.get();
            if (count <= policy.capacity()) {
                return RateLimitDecision.allowedDecision();
            }
            long retryAfter = redisTtlSeconds(key).orElse(policy.window().toSeconds());
            return RateLimitDecision.rejected(policy, retryAfter);
        }

        Bucket bucket = localBuckets.computeIfAbsent(key, ignored -> newBucket(policy));
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            return RateLimitDecision.allowedDecision();
        }
        long retryAfterSeconds =
                Math.max(1L, Duration.ofNanos(probe.getNanosToWaitForRefill()).toSeconds());
        return RateLimitDecision.rejected(policy, retryAfterSeconds);
    }

    private Optional<Long> incrementRedisCounter(String key, Duration ttl) {
        if (redisTemplate == null) {
            return Optional.empty();
        }
        try {
            Long value = redisTemplate.opsForValue().increment(key);
            if (value != null && value == 1L) {
                redisTemplate.expire(key, ttl);
            }
            return Optional.ofNullable(value);
        } catch (Exception exception) {
            failIfRedisRequired();
            return Optional.empty();
        }
    }

    private Optional<Long> redisTtlSeconds(String key) {
        if (redisTemplate == null) {
            return Optional.empty();
        }
        try {
            Long ttl = redisTemplate.getExpire(key);
            return ttl == null || ttl < 1 ? Optional.empty() : Optional.of(ttl);
        } catch (Exception exception) {
            failIfRedisRequired();
            return Optional.empty();
        }
    }

    private boolean hasRedisKey(String key) {
        if (redisTemplate == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(key));
        } catch (Exception exception) {
            failIfRedisRequired();
            return false;
        }
    }

    private void failIfRedisRequired() {
        if (requireRedisState) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, STATE_UNAVAILABLE_MESSAGE);
        }
    }

    private static Bucket newBucket(RateLimitPolicy policy) {
        Bandwidth limit = Bandwidth.classic(policy.capacity(), Refill.intervally(policy.capacity(), policy.window()));
        return Bucket.builder().addLimit(limit).build();
    }

    private static String rateLimitKey(RateLimitPolicy policy, String dimension, String rawValue) {
        return REDIS_LIMIT_PREFIX + policy.key() + ":" + dimension + ":" + hash(normalize(rawValue));
    }

    private static String blockKey(String clientIp) {
        return REDIS_BLOCK_PREFIX + hash(normalize(clientIp));
    }

    private static String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : "anonymous";
    }

    private static String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest is unavailable", exception);
        }
    }

    private static Duration positiveDuration(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }
}
