package com.example.monkey.user.infrastructure;

import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.shared.infrastructure.privacy.PiiCryptoService;
import com.example.monkey.user.domain.LoginAttemptPolicy;
import com.example.monkey.user.domain.LoginAttemptState;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class LoginAttemptService implements LoginAttemptPolicy {

    private static final String AUTH_STATE_UNAVAILABLE_MESSAGE = "authentication state store unavailable";
    private static final String IP_WINDOW_PREFIX = "login:ip-window:";
    private static final String PAIR_WINDOW_PREFIX = "login:pair-window:";
    private static final String PAIR_FAILURE_PREFIX = "login:pair-failure:";
    private static final String PAIR_LOCK_PREFIX = "login:pair-lock:";
    private static final Duration DEFAULT_WINDOW = Duration.ofMinutes(5);
    private static final Duration DEFAULT_LOCK = Duration.ofMinutes(10);

    private static final DefaultRedisScript<String> EVALUATE_ATTEMPT = new DefaultRedisScript<>("""
            local failures = tonumber(redis.call('get', KEYS[3]) or '0')
            local captcha = failures >= tonumber(ARGV[5]) and 1 or 0
            local lockTtl = redis.call('pttl', KEYS[4])
            if lockTtl == -1 then
                lockTtl = tonumber(ARGV[6])
            end
            if lockTtl > 0 then
                return '1|' .. captcha .. '|' .. lockTtl
            end

            local ipCount = redis.call('incr', KEYS[1])
            if ipCount == 1 then
                redis.call('pexpire', KEYS[1], ARGV[1])
            end
            local pairCount = redis.call('incr', KEYS[2])
            if pairCount == 1 then
                redis.call('pexpire', KEYS[2], ARGV[2])
            end

            local ipTtl = redis.call('pttl', KEYS[1])
            if ipTtl < 0 then
                ipTtl = tonumber(ARGV[1])
            end
            local pairTtl = redis.call('pttl', KEYS[2])
            if pairTtl < 0 then
                pairTtl = tonumber(ARGV[2])
            end
            local retry = 0
            if ipCount > tonumber(ARGV[3]) then
                retry = math.max(retry, ipTtl)
            end
            if pairCount > tonumber(ARGV[4]) then
                retry = math.max(retry, pairTtl)
            end
            local locked = retry > 0 and 1 or 0
            return locked .. '|' .. captcha .. '|' .. retry
            """, String.class);

    private static final DefaultRedisScript<String> RECORD_FAILURE = new DefaultRedisScript<>("""
            local failures = redis.call('incr', KEYS[1])
            if failures == 1 then
                redis.call('pexpire', KEYS[1], ARGV[1])
            end
            if failures >= tonumber(ARGV[2]) and redis.call('exists', KEYS[2]) == 0 then
                redis.call('psetex', KEYS[2], ARGV[3], '1')
            end
            return tostring(failures)
            """, String.class);

    private static final DefaultRedisScript<String> RECORD_SUCCESS = new DefaultRedisScript<>("""
            local ipCount = tonumber(redis.call('get', KEYS[1]) or '0')
            if ipCount > 1 then
                redis.call('decr', KEYS[1])
                ipCount = ipCount - 1
            elseif ipCount == 1 then
                redis.call('del', KEYS[1])
                ipCount = 0
            end
            redis.call('del', KEYS[2], KEYS[3], KEYS[4])
            return tostring(ipCount)
            """, String.class);

    private final StringRedisTemplate redisTemplate;
    private final boolean requireRedisState;
    private final int ipCapacity;
    private final Duration ipWindow;
    private final int pairCapacity;
    private final Duration pairWindow;
    private final int captchaThreshold;
    private final int failureLockThreshold;
    private final Duration lockDuration;
    private final PiiCryptoService piiCryptoService;
    private final Clock clock;
    private final Map<String, Counter> ipWindows = new ConcurrentHashMap<>();
    private final Map<String, Counter> pairWindows = new ConcurrentHashMap<>();
    private final Map<String, Counter> pairFailures = new ConcurrentHashMap<>();
    private final Map<String, Instant> pairLocks = new ConcurrentHashMap<>();
    private final Object localStateMonitor = new Object();

    @Autowired
    public LoginAttemptService(
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            @Value("${app.auth.login.ip-capacity:30}") int ipCapacity,
            @Value("${app.auth.login.ip-window-seconds:300}") long ipWindowSeconds,
            @Value("${app.auth.login.pair-capacity:10}") int pairCapacity,
            @Value("${app.auth.login.pair-window-seconds:300}") long pairWindowSeconds,
            @Value("${app.auth.login.captcha-threshold:3}") int captchaThreshold,
            @Value("${app.auth.login.failure-lock-threshold:10}") int failureLockThreshold,
            @Value("${app.auth.login.lock-seconds:600}") long lockSeconds,
            @Value("${app.auth.require-redis-state:false}") boolean requireRedisState,
            PiiCryptoService piiCryptoService) {
        this(
                redisTemplateProvider.getIfAvailable(),
                requireRedisState,
                ipCapacity,
                Duration.ofSeconds(ipWindowSeconds),
                pairCapacity,
                Duration.ofSeconds(pairWindowSeconds),
                captchaThreshold,
                failureLockThreshold,
                Duration.ofSeconds(lockSeconds),
                piiCryptoService,
                Clock.systemUTC());
    }

    LoginAttemptService(
            StringRedisTemplate redisTemplate,
            boolean requireRedisState,
            int ipCapacity,
            Duration ipWindow,
            int pairCapacity,
            Duration pairWindow,
            int captchaThreshold,
            int failureLockThreshold,
            Duration lockDuration,
            PiiCryptoService piiCryptoService,
            Clock clock) {
        this.redisTemplate = redisTemplate;
        this.requireRedisState = requireRedisState;
        if (requireRedisState && redisTemplate == null) {
            throw new IllegalStateException(AUTH_STATE_UNAVAILABLE_MESSAGE);
        }
        this.ipCapacity = positiveInt(ipCapacity, 30);
        this.ipWindow = positiveDuration(ipWindow, DEFAULT_WINDOW);
        this.pairCapacity = positiveInt(pairCapacity, 10);
        this.pairWindow = positiveDuration(pairWindow, DEFAULT_WINDOW);
        this.captchaThreshold = positiveInt(captchaThreshold, 3);
        this.failureLockThreshold = positiveInt(failureLockThreshold, 10);
        this.lockDuration = positiveDuration(lockDuration, DEFAULT_LOCK);
        this.piiCryptoService = piiCryptoService;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Override
    public LoginAttemptState evaluate(String username, String clientIp) {
        AttemptKeys keys = attemptKeys(username, clientIp);
        Optional<String> redisResult = execute(
                EVALUATE_ATTEMPT,
                keys.redisKeys(),
                Long.toString(ipWindow.toMillis()),
                Long.toString(pairWindow.toMillis()),
                Integer.toString(ipCapacity),
                Integer.toString(pairCapacity),
                Integer.toString(captchaThreshold),
                Long.toString(lockDuration.toMillis()));
        return redisResult.map(LoginAttemptService::parseEvaluation).orElseGet(() -> evaluateLocal(keys));
    }

    @Override
    public void recordFailure(String username, String clientIp) {
        AttemptKeys keys = attemptKeys(username, clientIp);
        Optional<String> redisResult = execute(
                RECORD_FAILURE,
                List.of(keys.pairFailureKey(), keys.pairLockKey()),
                Long.toString(lockDuration.toMillis()),
                Integer.toString(failureLockThreshold),
                Long.toString(lockDuration.toMillis()));
        if (redisResult.isEmpty()) {
            recordFailureLocal(keys);
        }
    }

    @Override
    public void recordSuccess(String username, String clientIp) {
        AttemptKeys keys = attemptKeys(username, clientIp);
        Optional<String> redisResult = execute(RECORD_SUCCESS, keys.successKeys());
        if (redisResult.isEmpty()) {
            recordSuccessLocal(keys);
        }
    }

    private LoginAttemptState evaluateLocal(AttemptKeys keys) {
        synchronized (localStateMonitor) {
            Instant now = Instant.now(clock);
            Counter failures = activeCounter(pairFailures, keys.pairFailureKey(), now);
            boolean captchaRequired = failures != null && failures.count() >= captchaThreshold;
            Instant lockExpiresAt = pairLocks.get(keys.pairLockKey());
            if (lockExpiresAt != null && now.isBefore(lockExpiresAt)) {
                return LoginAttemptState.locked(captchaRequired, remainingSeconds(now, lockExpiresAt));
            }
            if (lockExpiresAt != null) {
                pairLocks.remove(keys.pairLockKey());
            }

            Counter ipCounter = incrementLocal(ipWindows, keys.ipWindowKey(), ipWindow, now);
            Counter pairCounter = incrementLocal(pairWindows, keys.pairWindowKey(), pairWindow, now);
            long retryAfterSeconds = 0L;
            if (ipCounter.count() > ipCapacity) {
                retryAfterSeconds = Math.max(retryAfterSeconds, remainingSeconds(now, ipCounter.expiresAt()));
            }
            if (pairCounter.count() > pairCapacity) {
                retryAfterSeconds = Math.max(retryAfterSeconds, remainingSeconds(now, pairCounter.expiresAt()));
            }
            return retryAfterSeconds > 0L
                    ? LoginAttemptState.locked(captchaRequired, retryAfterSeconds)
                    : LoginAttemptState.allowed(captchaRequired);
        }
    }

    private void recordFailureLocal(AttemptKeys keys) {
        synchronized (localStateMonitor) {
            Instant now = Instant.now(clock);
            Counter failures = incrementLocal(pairFailures, keys.pairFailureKey(), lockDuration, now);
            if (failures.count() >= failureLockThreshold) {
                pairLocks.putIfAbsent(keys.pairLockKey(), now.plus(lockDuration));
            }
        }
    }

    private void recordSuccessLocal(AttemptKeys keys) {
        synchronized (localStateMonitor) {
            Instant now = Instant.now(clock);
            Counter ipCounter = activeCounter(ipWindows, keys.ipWindowKey(), now);
            if (ipCounter != null && ipCounter.count() > 1L) {
                ipWindows.put(keys.ipWindowKey(), new Counter(ipCounter.count() - 1L, ipCounter.expiresAt()));
            } else {
                ipWindows.remove(keys.ipWindowKey());
            }
            pairWindows.remove(keys.pairWindowKey());
            pairFailures.remove(keys.pairFailureKey());
            pairLocks.remove(keys.pairLockKey());
        }
    }

    private AttemptKeys attemptKeys(String username, String clientIp) {
        String usernameHash = piiCryptoService == null ? null : piiCryptoService.blindIndexText(username);
        if (!StringUtils.hasText(usernameHash)) {
            throw unavailable();
        }
        String ipHash = sha256Hex(normalizeIp(clientIp));
        String pairScope = usernameHash + ":" + ipHash;
        return new AttemptKeys(
                IP_WINDOW_PREFIX + ipHash,
                PAIR_WINDOW_PREFIX + pairScope,
                PAIR_FAILURE_PREFIX + pairScope,
                PAIR_LOCK_PREFIX + pairScope);
    }

    private Optional<String> execute(DefaultRedisScript<String> script, List<String> keys, Object... arguments) {
        if (redisTemplate == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(redisTemplate.execute(script, keys, arguments));
        } catch (Exception exception) {
            if (requireRedisState) {
                throw unavailable();
            }
            return Optional.empty();
        }
    }

    private static LoginAttemptState parseEvaluation(String result) {
        try {
            String[] parts = result.split("\\|", -1);
            if (parts.length != 3) {
                throw new IllegalArgumentException("unexpected login state result");
            }
            boolean locked = "1".equals(parts[0]);
            boolean captchaRequired = "1".equals(parts[1]);
            long retryMilliseconds = Long.parseLong(parts[2]);
            return locked
                    ? LoginAttemptState.locked(captchaRequired, ceilMilliseconds(retryMilliseconds))
                    : LoginAttemptState.allowed(captchaRequired);
        } catch (RuntimeException exception) {
            throw unavailable();
        }
    }

    private static Counter incrementLocal(Map<String, Counter> counters, String key, Duration ttl, Instant now) {
        Counter current = activeCounter(counters, key, now);
        Counter updated = current == null
                ? new Counter(1L, now.plus(ttl))
                : new Counter(current.count() + 1L, current.expiresAt());
        counters.put(key, updated);
        return updated;
    }

    private static Counter activeCounter(Map<String, Counter> counters, String key, Instant now) {
        Counter current = counters.get(key);
        if (current != null && !now.isBefore(current.expiresAt())) {
            counters.remove(key);
            return null;
        }
        return current;
    }

    private static long remainingSeconds(Instant now, Instant expiresAt) {
        return ceilMilliseconds(Math.max(1L, Duration.between(now, expiresAt).toMillis()));
    }

    private static long ceilMilliseconds(long milliseconds) {
        if (milliseconds <= 0L) {
            return 1L;
        }
        return 1L + ((milliseconds - 1L) / 1_000L);
    }

    private static int positiveInt(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private static Duration positiveDuration(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }

    private static String normalizeIp(String clientIp) {
        return StringUtils.hasText(clientIp) ? clientIp.trim().toLowerCase(Locale.ROOT) : "unknown";
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest is unavailable", exception);
        }
    }

    private static BusinessException unavailable() {
        return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, AUTH_STATE_UNAVAILABLE_MESSAGE);
    }

    private record Counter(long count, Instant expiresAt) {}

    private record AttemptKeys(String ipWindowKey, String pairWindowKey, String pairFailureKey, String pairLockKey) {

        private List<String> redisKeys() {
            return List.of(ipWindowKey, pairWindowKey, pairFailureKey, pairLockKey);
        }

        private List<String> successKeys() {
            return List.of(ipWindowKey, pairWindowKey, pairFailureKey, pairLockKey);
        }
    }
}
