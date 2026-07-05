package com.example.monkey.user.infrastructure;

import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.user.domain.LoginAttemptPolicy;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
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
public class LoginAttemptService implements LoginAttemptPolicy {

    private static final String TOO_MANY_ATTEMPTS_MESSAGE = "too many login attempts";
    private static final String TEMPORARILY_LOCKED_MESSAGE = "login temporarily locked";
    private static final String AUTH_STATE_UNAVAILABLE_MESSAGE = "authentication state store unavailable";

    private static final String REDIS_WINDOW_PREFIX = "login:window:";
    private static final String REDIS_FAILURE_PREFIX = "login:failure:";
    private static final String REDIS_CAPTCHA_PREFIX = "login:captcha:";
    private static final String REDIS_LOCK_PREFIX = "login:lock:";

    private final StringRedisTemplate redisTemplate;
    private final boolean requireRedisState;
    private final int maxAttemptsPerWindow;
    private final Duration windowDuration;
    private final int failureLockThreshold;
    private final int captchaThreshold;
    private final Duration lockDuration;
    private final Map<String, Bucket> windowBuckets = new ConcurrentHashMap<>();
    private final Map<String, Counter> failureCounters = new ConcurrentHashMap<>();
    private final Map<String, Instant> captchaExpirations = new ConcurrentHashMap<>();
    private final Map<String, Instant> lockExpirations = new ConcurrentHashMap<>();

    @Autowired
    public LoginAttemptService(
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            @Value("${app.auth.login.max-attempts-per-window:5}") int maxAttemptsPerWindow,
            @Value("${app.auth.login.window-seconds:60}") long windowSeconds,
            @Value("${app.auth.login.failure-lock-threshold:5}") int failureLockThreshold,
            @Value("${app.auth.login.captcha-threshold:5}") int captchaThreshold,
            @Value("${app.auth.login.lock-seconds:900}") long lockSeconds,
            @Value("${app.auth.require-redis-state:false}") boolean requireRedisState) {
        this(
                redisTemplateProvider.getIfAvailable(),
                requireRedisState,
                maxAttemptsPerWindow,
                Duration.ofSeconds(windowSeconds),
                failureLockThreshold,
                captchaThreshold,
                Duration.ofSeconds(lockSeconds));
    }

    LoginAttemptService(
            StringRedisTemplate redisTemplate,
            int maxAttemptsPerWindow,
            Duration windowDuration,
            int failureLockThreshold,
            Duration lockDuration) {
        this(
                redisTemplate,
                false,
                maxAttemptsPerWindow,
                windowDuration,
                failureLockThreshold,
                failureLockThreshold,
                lockDuration);
    }

    LoginAttemptService(
            StringRedisTemplate redisTemplate,
            boolean requireRedisState,
            int maxAttemptsPerWindow,
            Duration windowDuration,
            int failureLockThreshold,
            Duration lockDuration) {
        this(
                redisTemplate,
                requireRedisState,
                maxAttemptsPerWindow,
                windowDuration,
                failureLockThreshold,
                failureLockThreshold,
                lockDuration);
    }

    LoginAttemptService(
            StringRedisTemplate redisTemplate,
            int maxAttemptsPerWindow,
            Duration windowDuration,
            int failureLockThreshold,
            int captchaThreshold,
            Duration lockDuration) {
        this(
                redisTemplate,
                false,
                maxAttemptsPerWindow,
                windowDuration,
                failureLockThreshold,
                captchaThreshold,
                lockDuration);
    }

    LoginAttemptService(
            StringRedisTemplate redisTemplate,
            boolean requireRedisState,
            int maxAttemptsPerWindow,
            Duration windowDuration,
            int failureLockThreshold,
            int captchaThreshold,
            Duration lockDuration) {
        this.redisTemplate = redisTemplate;
        this.requireRedisState = requireRedisState;
        if (requireRedisState && redisTemplate == null) {
            throw new IllegalStateException(AUTH_STATE_UNAVAILABLE_MESSAGE);
        }
        this.maxAttemptsPerWindow = Math.max(1, maxAttemptsPerWindow);
        this.windowDuration = positiveDuration(windowDuration, Duration.ofMinutes(1));
        this.failureLockThreshold = Math.max(1, failureLockThreshold);
        this.captchaThreshold = Math.max(1, captchaThreshold);
        this.lockDuration = positiveDuration(lockDuration, Duration.ofMinutes(15));
    }

    @Override
    public void enforceAllowed(String username, String clientIp) {
        AttemptKeys keys = attemptKeys(username, clientIp);
        if (isLocked(keys.usernameKey()) || isLocked(keys.ipKey()) || isLocked(keys.pairKey())) {
            throw new BusinessException(ErrorCode.RATE_LIMIT, TEMPORARILY_LOCKED_MESSAGE);
        }

        if (!consumeWindowToken(keys.usernameKey())
                || !consumeWindowToken(keys.ipKey())
                || !consumeWindowToken(keys.pairKey())) {
            throw new BusinessException(ErrorCode.RATE_LIMIT, TOO_MANY_ATTEMPTS_MESSAGE);
        }
    }

    @Override
    public void recordFailure(String username, String clientIp) {
        AttemptKeys keys = attemptKeys(username, clientIp);
        recordFailureForKey(keys.usernameKey());
        recordFailureForKey(keys.ipKey());
        recordFailureForKey(keys.pairKey());
    }

    @Override
    public void recordSuccess(String username, String clientIp) {
        AttemptKeys keys = attemptKeys(username, clientIp);
        clearKey(keys.usernameKey());
        clearKey(keys.pairKey());
    }

    private void recordFailureForKey(String key) {
        long failures = incrementCounter(REDIS_FAILURE_PREFIX, failureCounters, key, lockDuration);
        if (failures >= captchaThreshold) {
            requireCaptcha(key);
        }
        if (failures >= failureLockThreshold) {
            lock(key);
        }
    }

    private void clearKey(String key) {
        deleteRedisKey(REDIS_WINDOW_PREFIX + key);
        deleteRedisKey(REDIS_FAILURE_PREFIX + key);
        deleteRedisKey(REDIS_CAPTCHA_PREFIX + key);
        deleteRedisKey(REDIS_LOCK_PREFIX + key);
        windowBuckets.remove(key);
        failureCounters.remove(key);
        captchaExpirations.remove(key);
        lockExpirations.remove(key);
    }

    private boolean consumeWindowToken(String key) {
        Optional<Long> redisCount = incrementRedisCounter(REDIS_WINDOW_PREFIX + key, windowDuration);
        if (redisCount.isPresent()) {
            return redisCount.get() <= maxAttemptsPerWindow;
        }
        return windowBuckets.computeIfAbsent(key, ignored -> newWindowBucket()).tryConsume(1);
    }

    private Bucket newWindowBucket() {
        Bandwidth limit = Bandwidth.builder()
                .capacity(maxAttemptsPerWindow)
                .refillIntervally(maxAttemptsPerWindow, windowDuration)
                .build();
        return Bucket.builder().addLimit(limit).build();
    }

    @Override
    public boolean requiresCaptcha(String username, String clientIp) {
        AttemptKeys keys = attemptKeys(username, clientIp);
        return requiresCaptchaForKey(keys.usernameKey())
                || requiresCaptchaForKey(keys.ipKey())
                || requiresCaptchaForKey(keys.pairKey());
    }

    private boolean requiresCaptchaForKey(String key) {
        if (requireRedisState) {
            return hasRedisKey(REDIS_CAPTCHA_PREFIX + key);
        }
        Instant now = Instant.now();
        Instant memoryCaptchaExpiresAt = captchaExpirations.get(key);
        if (memoryCaptchaExpiresAt != null && now.isBefore(memoryCaptchaExpiresAt)) {
            return true;
        }
        if (memoryCaptchaExpiresAt != null) {
            captchaExpirations.remove(key);
        }
        return hasRedisKey(REDIS_CAPTCHA_PREFIX + key);
    }

    private long incrementCounter(String redisPrefix, Map<String, Counter> memoryCounters, String key, Duration ttl) {
        Optional<Long> redisCount = incrementRedisCounter(redisPrefix + key, ttl);
        return redisCount.orElseGet(() -> incrementMemoryCounter(memoryCounters, key, ttl));
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
        } catch (Exception e) {
            failIfRedisRequired();
            return Optional.empty();
        }
    }

    private long incrementMemoryCounter(Map<String, Counter> counters, String key, Duration ttl) {
        Instant now = Instant.now();
        Counter counter = counters.compute(key, (ignored, current) -> {
            if (current == null || !now.isBefore(current.expiresAt())) {
                return new Counter(1, now.plus(ttl));
            }
            return new Counter(current.count() + 1, current.expiresAt());
        });
        return counter.count();
    }

    private boolean isLocked(String key) {
        if (requireRedisState) {
            return hasRedisKey(REDIS_LOCK_PREFIX + key);
        }
        Instant now = Instant.now();
        Instant memoryLockExpiresAt = lockExpirations.get(key);
        if (memoryLockExpiresAt != null && now.isBefore(memoryLockExpiresAt)) {
            return true;
        }
        if (memoryLockExpiresAt != null) {
            lockExpirations.remove(key);
        }
        return hasRedisKey(REDIS_LOCK_PREFIX + key);
    }

    private void lock(String key) {
        Instant expiresAt = Instant.now().plus(lockDuration);
        if (!requireRedisState) {
            lockExpirations.put(key, expiresAt);
        }
        setRedisLock(REDIS_LOCK_PREFIX + key);
    }

    private void requireCaptcha(String key) {
        Duration captchaDuration = lockDuration.plus(windowDuration);
        if (!requireRedisState) {
            captchaExpirations.put(key, Instant.now().plus(captchaDuration));
        }
        setRedisValue(REDIS_CAPTCHA_PREFIX + key, "1", captchaDuration);
    }

    private boolean hasRedisKey(String key) {
        if (redisTemplate == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(key));
        } catch (Exception e) {
            failIfRedisRequired();
            return false;
        }
    }

    private void setRedisLock(String key) {
        setRedisValue(key, "1", lockDuration);
    }

    private void setRedisValue(String key, String value, Duration ttl) {
        if (redisTemplate == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(key, value, ttl);
        } catch (Exception e) {
            failIfRedisRequired();
        }
    }

    private void deleteRedisKey(String key) {
        if (redisTemplate == null) {
            return;
        }
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            failIfRedisRequired();
        }
    }

    private void failIfRedisRequired() {
        if (requireRedisState) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, AUTH_STATE_UNAVAILABLE_MESSAGE);
        }
    }

    private static AttemptKeys attemptKeys(String username, String clientIp) {
        String normalizedUsername =
                StringUtils.hasText(username) ? username.trim().toLowerCase(Locale.ROOT) : "anonymous";
        String normalizedIp = StringUtils.hasText(clientIp) ? clientIp.trim() : "unknown";
        return new AttemptKeys(
                sha256Hex("user|" + normalizedUsername),
                sha256Hex("ip|" + normalizedIp),
                sha256Hex("pair|" + normalizedUsername + "|" + normalizedIp));
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest is unavailable", e);
        }
    }

    private static Duration positiveDuration(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }

    private record Counter(long count, Instant expiresAt) {}

    private record AttemptKeys(String usernameKey, String ipKey, String pairKey) {}
}
