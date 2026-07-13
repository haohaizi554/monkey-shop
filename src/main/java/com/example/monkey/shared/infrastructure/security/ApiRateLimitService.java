package com.example.monkey.shared.infrastructure.security;

import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.shared.domain.security.ApiRateLimiter;
import com.example.monkey.shared.domain.security.ApiRateLimiter.RateLimitDecision;
import com.example.monkey.shared.domain.security.ApiRateLimiter.RegistrationIdentity;
import com.example.monkey.shared.domain.security.RateLimitPolicy;
import com.example.monkey.shared.infrastructure.privacy.PiiCryptoService;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
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
import org.springframework.beans.factory.annotation.Autowired;
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
    private final RateLimitProperties properties;
    private final PiiCryptoService piiCryptoService;
    private final Map<String, Bucket> localBuckets = new ConcurrentHashMap<>();
    private final Map<String, Long> localBlocks = new ConcurrentHashMap<>();

    @Autowired
    public ApiRateLimitService(
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            @Value("${app.rate-limit.enabled:true}") boolean enabled,
            @Value("${app.rate-limit.require-redis-state:${app.auth.require-redis-state:false}}")
                    boolean requireRedisState,
            @Value("${app.rate-limit.honeypot.block-seconds:86400}") long honeypotBlockSeconds,
            RateLimitProperties properties,
            PiiCryptoService piiCryptoService) {
        this(
                redisTemplateProvider.getIfAvailable(),
                enabled,
                requireRedisState,
                Duration.ofSeconds(honeypotBlockSeconds),
                properties,
                piiCryptoService);
    }

    ApiRateLimitService(
            StringRedisTemplate redisTemplate,
            boolean enabled,
            boolean requireRedisState,
            Duration honeypotBlockDuration) {
        this(redisTemplate, enabled, requireRedisState, honeypotBlockDuration, RateLimitProperties.defaults(), null);
    }

    ApiRateLimitService(
            StringRedisTemplate redisTemplate,
            boolean enabled,
            boolean requireRedisState,
            Duration honeypotBlockDuration,
            RateLimitProperties properties,
            PiiCryptoService piiCryptoService) {
        this.redisTemplate = redisTemplate;
        this.enabled = enabled;
        this.requireRedisState = requireRedisState;
        if (enabled && requireRedisState && redisTemplate == null) {
            throw new IllegalStateException(STATE_UNAVAILABLE_MESSAGE);
        }
        this.honeypotBlockDuration = positiveDuration(honeypotBlockDuration, DEFAULT_HONEYPOT_BLOCK);
        this.properties = properties == null ? RateLimitProperties.defaults() : properties;
        this.piiCryptoService = piiCryptoService;
    }

    @Override
    public RateLimitDecision consume(RateLimitPolicy policy, String clientIp, String userKey) {
        if (!enabled || policy == null) {
            return RateLimitDecision.allowedDecision();
        }
        String normalizedClientIp = normalize(clientIp);
        if (RateLimitPolicy.REGISTER.equals(policy)) {
            RateLimitProperties.Register register = properties.register();
            return consumeDimension(
                    policy, "edge-ip", normalizedClientIp, register.edgeCapacity(), register.edgeWindow());
        }
        String effectiveUserKey = effectiveUserKey(userKey, normalizedClientIp);
        RateLimitDecision ipDecision = consumeDimension(policy, "ip", normalizedClientIp);
        if (!ipDecision.allowed()) {
            return ipDecision;
        }
        RateLimitDecision userDecision = consumeDimension(policy, "user", effectiveUserKey);
        if (!userDecision.allowed()) {
            return userDecision;
        }
        return consumeDimension(policy, "endpoint", endpointScope(policy, normalizedClientIp));
    }

    @Override
    public RateLimitDecision consumeRegistrationIdentity(RegistrationIdentity identity, String rawIdentity) {
        if (!enabled || identity == null) {
            return RateLimitDecision.allowedDecision();
        }
        String identityHash = identityHash(identity, rawIdentity);
        if (!StringUtils.hasText(identityHash)) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, STATE_UNAVAILABLE_MESSAGE);
        }
        RateLimitProperties.Register register = properties.register();
        String key = REDIS_LIMIT_PREFIX + RateLimitPolicy.REGISTER.key() + ":" + identity.key() + ":" + identityHash;
        return consumeCounter(RateLimitPolicy.REGISTER, key, register.identityCapacity(), register.identityWindow());
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

    private static String effectiveUserKey(String userKey, String normalizedClientIp) {
        String normalizedUserKey = normalize(userKey);
        if ("anonymous".equals(normalizedUserKey)) {
            return "anonymous-ip:" + normalizedClientIp;
        }
        return normalizedUserKey;
    }

    private static String endpointScope(RateLimitPolicy policy, String normalizedClientIp) {
        return policy.key() + ":ip:" + normalizedClientIp;
    }

    private RateLimitDecision consumeDimension(RateLimitPolicy policy, String dimension, String rawValue) {
        return consumeDimension(policy, dimension, rawValue, policy.capacity(), policy.window());
    }

    private RateLimitDecision consumeDimension(
            RateLimitPolicy policy, String dimension, String rawValue, long capacity, Duration window) {
        String key = rateLimitKey(policy, dimension, rawValue);
        return consumeCounter(policy, key, capacity, window);
    }

    private RateLimitDecision consumeCounter(RateLimitPolicy policy, String key, long capacity, Duration window) {
        Optional<Long> redisCount = incrementRedisCounter(key, window);
        if (redisCount.isPresent()) {
            long count = redisCount.get();
            if (count <= capacity) {
                return RateLimitDecision.allowedDecision();
            }
            long retryAfter = redisTtlSeconds(key).orElse(window.toSeconds());
            return RateLimitDecision.rejected(policy, retryAfter);
        }

        Bucket bucket = localBuckets.computeIfAbsent(key, ignored -> newBucket(capacity, window));
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            return RateLimitDecision.allowedDecision();
        }
        long retryAfterSeconds = ceilRetryAfterSeconds(probe.getNanosToWaitForRefill());
        return RateLimitDecision.rejected(policy, retryAfterSeconds);
    }

    static long ceilRetryAfterSeconds(long nanos) {
        if (nanos <= 0L) {
            return 1L;
        }
        return 1L + ((nanos - 1L) / 1_000_000_000L);
    }

    private String identityHash(RegistrationIdentity identity, String rawIdentity) {
        if (piiCryptoService == null) {
            return null;
        }
        return switch (identity) {
            case USERNAME -> piiCryptoService.blindIndexText(rawIdentity);
            case PHONE -> piiCryptoService.blindIndexPhone(rawIdentity);
        };
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

    private static Bucket newBucket(long capacity, Duration window) {
        Bandwidth limit = Bandwidth.builder()
                .capacity(capacity)
                .refillIntervally(capacity, window)
                .build();
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
