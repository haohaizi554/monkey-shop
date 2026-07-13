package com.example.monkey.user.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.shared.infrastructure.privacy.PiiCryptoService;
import com.example.monkey.user.domain.LoginAttemptState;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

class LoginAttemptServiceTest {

    private static final String IP_A = "203.0.113.10";
    private static final String IP_B = "203.0.113.11";
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-07-14T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void twoPairFailuresDoNotRequireCaptchaOrLock() {
        LoginAttemptService service = localService(FIXED_CLOCK);

        service.recordFailure("alice", IP_A);
        service.recordFailure("alice", IP_A);

        assertThat(service.evaluate("alice", IP_A)).isEqualTo(LoginAttemptState.allowed(false));
    }

    @Test
    void captchaBeginsAfterThreePairFailures() {
        LoginAttemptService service = localService(FIXED_CLOCK);

        for (int i = 0; i < 3; i++) {
            service.recordFailure("alice", IP_A);
        }

        assertThat(service.evaluate("Alice", IP_A)).isEqualTo(LoginAttemptState.allowed(true));
    }

    @Test
    void tenPairFailuresCreateTenMinutePairLockWithRemainingRetry() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-14T00:00:00Z"));
        LoginAttemptService service = localService(clock);

        for (int i = 0; i < 10; i++) {
            service.recordFailure("alice", IP_A);
        }
        clock.advance(Duration.ofSeconds(90));

        assertThat(service.evaluate("alice", IP_A)).isEqualTo(LoginAttemptState.locked(true, 510));
    }

    @Test
    void pairLockDoesNotAffectSameUsernameOrIpInOtherPairs() {
        LoginAttemptService service = localService(FIXED_CLOCK);

        for (int i = 0; i < 10; i++) {
            service.recordFailure("alice", IP_A);
        }

        assertThat(service.evaluate("alice", IP_A).locked()).isTrue();
        assertThat(service.evaluate("alice", IP_B)).isEqualTo(LoginAttemptState.allowed(false));
        assertThat(service.evaluate("bob", IP_A)).isEqualTo(LoginAttemptState.allowed(false));
    }

    @Test
    void pairWindowAllowsTenAttemptsThenReturnsRemainingTtl() {
        LoginAttemptService service = localService(FIXED_CLOCK, 100, 10);

        for (int i = 0; i < 10; i++) {
            assertThat(service.evaluate("alice", IP_A).locked()).isFalse();
        }

        assertThat(service.evaluate("alice", IP_A)).isEqualTo(LoginAttemptState.locked(false, 300));
    }

    @Test
    void sharedIpWindowAllowsThirtyAttemptsAcrossDifferentUsernames() {
        LoginAttemptService service = localService(FIXED_CLOCK);

        for (int i = 0; i < 30; i++) {
            assertThat(service.evaluate("user-" + i, IP_A).locked()).isFalse();
        }

        assertThat(service.evaluate("user-31", IP_A)).isEqualTo(LoginAttemptState.locked(false, 300));
    }

    @Test
    void successfulLoginClearsPairStateAndDecrementsSharedIpCounter() {
        LoginAttemptService service = localService(FIXED_CLOCK, 2, 10);

        assertThat(service.evaluate("alice", IP_A).locked()).isFalse();
        assertThat(service.evaluate("bob", IP_A).locked()).isFalse();
        service.recordFailure("alice", IP_A);
        service.recordFailure("alice", IP_A);
        service.recordSuccess("alice", IP_A);

        assertThat(service.evaluate("carol", IP_A).locked()).isFalse();
        assertThat(service.evaluate("alice", IP_A).captchaRequired()).isFalse();
        assertThat(service.evaluate("dave", IP_A).locked()).isTrue();
    }

    @Test
    void redisEvaluationUsesAtomicIncrementTtlAndHmacOnlyKeys() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        PiiCryptoService piiCryptoService = hmacService();
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn("0|0|0");
        LoginAttemptService service = redisService(redisTemplate, piiCryptoService, false);

        assertThat(service.evaluate("Alice", IP_A)).isEqualTo(LoginAttemptState.allowed(false));

        RedisExecution execution = captureExecution(redisTemplate);
        assertThat(execution.script().getScriptAsString())
                .contains("redis.call('incr'", "redis.call('pexpire'", "redis.call('pttl'");
        String expectedUsernameHmac = testHmac("alice");
        assertThat(execution.keys())
                .hasSize(4)
                .anySatisfy(key -> assertThat(key).contains(expectedUsernameHmac))
                .allSatisfy(key -> assertThat(key).doesNotContain("Alice", "alice", IP_A));
    }

    @Test
    void redisRetryAfterRoundsRemainingMillisecondsUp() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn("1|0|7001");
        LoginAttemptService service = redisService(redisTemplate, hmacService(), false);

        assertThat(service.evaluate("alice", IP_A)).isEqualTo(LoginAttemptState.locked(false, 8));
    }

    @Test
    void redisFailureAndSuccessTransitionsAreAtomicScripts() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn("3", "0");
        LoginAttemptService service = redisService(redisTemplate, hmacService(), false);

        service.recordFailure("alice", IP_A);
        service.recordSuccess("alice", IP_A);

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<RedisScript> scripts = ArgumentCaptor.forClass(RedisScript.class);
        verify(redisTemplate, times(2)).execute(scripts.capture(), anyList(), any(Object[].class));
        assertThat(scripts.getAllValues().get(0).getScriptAsString())
                .contains("redis.call('incr'", "redis.call('pexpire'", "redis.call('psetex'");
        assertThat(scripts.getAllValues().get(1).getScriptAsString()).contains("redis.call('decr'", "redis.call('del'");
    }

    @Test
    void requiredRedisStateFailsClosedWhenScriptExecutionFails() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RuntimeException("redis unavailable"));
        LoginAttemptService service = redisService(redisTemplate, hmacService(), true);

        assertServiceUnavailable(() -> service.evaluate("alice", IP_A));
    }

    @Test
    void missingHmacMaterialFailsClosedWithoutBuildingPlainUsernameKey() {
        PiiCryptoService piiCryptoService = mock(PiiCryptoService.class);
        LoginAttemptService service = redisService(mock(StringRedisTemplate.class), piiCryptoService, false);

        assertServiceUnavailable(() -> service.evaluate("alice", IP_A));
    }

    @Test
    void requiredRedisStateRejectsMissingRedisTemplate() {
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> new LoginAttemptService(
                        null,
                        true,
                        30,
                        Duration.ofMinutes(5),
                        10,
                        Duration.ofMinutes(5),
                        3,
                        10,
                        Duration.ofMinutes(10),
                        hmacService(),
                        FIXED_CLOCK))
                .withMessage("authentication state store unavailable");
    }

    private static LoginAttemptService localService(Clock clock) {
        return localService(clock, 30, 10);
    }

    private static LoginAttemptService localService(Clock clock, int ipCapacity, int pairCapacity) {
        return new LoginAttemptService(
                null,
                false,
                ipCapacity,
                Duration.ofMinutes(5),
                pairCapacity,
                Duration.ofMinutes(5),
                3,
                10,
                Duration.ofMinutes(10),
                hmacService(),
                clock);
    }

    private static LoginAttemptService redisService(
            StringRedisTemplate redisTemplate, PiiCryptoService piiCryptoService, boolean requireRedisState) {
        return new LoginAttemptService(
                redisTemplate,
                requireRedisState,
                30,
                Duration.ofMinutes(5),
                10,
                Duration.ofMinutes(5),
                3,
                10,
                Duration.ofMinutes(10),
                piiCryptoService,
                FIXED_CLOCK);
    }

    private static PiiCryptoService hmacService() {
        PiiCryptoService piiCryptoService = mock(PiiCryptoService.class);
        when(piiCryptoService.blindIndexText(anyString()))
                .thenAnswer(invocation -> testHmac(invocation.getArgument(0, String.class)));
        return piiCryptoService;
    }

    private static String testHmac(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            return HexFormat.of()
                    .formatHex(digest.digest(("test-hmac|" + normalized).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static RedisExecution captureExecution(StringRedisTemplate redisTemplate) {
        ArgumentCaptor<RedisScript> script = ArgumentCaptor.forClass(RedisScript.class);
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(redisTemplate).execute(script.capture(), keys.capture(), any(Object[].class));
        return new RedisExecution(script.getValue(), keys.getValue());
    }

    private static void assertServiceUnavailable(Runnable action) {
        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(action::run)
                .withMessage("authentication state store unavailable")
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
    }

    private record RedisExecution(RedisScript<?> script, List<String> keys) {}

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
