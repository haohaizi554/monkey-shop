package com.example.monkey.user.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class LoginAttemptServiceTest {

    @Test
    void deniesMoreThanFiveAttemptsPerMinuteForSameUsernameAndIp() {
        LoginAttemptService service =
                new LoginAttemptService(null, 5, Duration.ofMinutes(1), 5, Duration.ofMinutes(15));

        for (int i = 0; i < 5; i++) {
            service.enforceAllowed("Alice", "203.0.113.10");
        }

        assertRateLimit(() -> service.enforceAllowed("alice", "203.0.113.10"), "too many login attempts");
    }

    @Test
    void invalidThresholdsAndDurationsFallBackToSafeMinimums() {
        LoginAttemptService service = new LoginAttemptService(null, 0, Duration.ZERO, 0, Duration.ZERO);

        service.enforceAllowed("alice", "203.0.113.10");

        assertRateLimit(() -> service.enforceAllowed("alice", "203.0.113.10"), "too many login attempts");
    }

    @Test
    void successClearsUsernameLockButPreservesSharedIpLock() {
        LoginAttemptService service =
                new LoginAttemptService(null, 10, Duration.ofMinutes(1), 5, Duration.ofMinutes(15));

        for (int i = 0; i < 5; i++) {
            service.recordFailure("alice", "203.0.113.10");
        }

        assertRateLimit(() -> service.enforceAllowed("alice", "203.0.113.10"), "login temporarily locked");

        service.recordSuccess("alice", "203.0.113.10");

        service.enforceAllowed("alice", "203.0.113.11");
        assertRateLimit(() -> service.enforceAllowed("bob", "203.0.113.10"), "login temporarily locked");
    }

    @Test
    void throttlesAttemptsByUsernameAcrossDifferentIps() {
        LoginAttemptService service =
                new LoginAttemptService(null, 2, Duration.ofMinutes(1), 5, Duration.ofMinutes(15));

        service.enforceAllowed("alice", "203.0.113.10");
        service.enforceAllowed("alice", "203.0.113.11");

        assertRateLimit(() -> service.enforceAllowed("alice", "203.0.113.12"), "too many login attempts");
    }

    @Test
    void throttlesAttemptsByIpAcrossDifferentUsernames() {
        LoginAttemptService service =
                new LoginAttemptService(null, 2, Duration.ofMinutes(1), 5, Duration.ofMinutes(15));

        service.enforceAllowed("alice", "203.0.113.10");
        service.enforceAllowed("bob", "203.0.113.10");

        assertRateLimit(() -> service.enforceAllowed("carol", "203.0.113.10"), "too many login attempts");
    }

    @Test
    void locksUsernameAfterFailuresAcrossDifferentIps() {
        LoginAttemptService service =
                new LoginAttemptService(null, 10, Duration.ofMinutes(1), 3, Duration.ofMinutes(15));

        service.recordFailure("alice", "203.0.113.10");
        service.recordFailure("alice", "203.0.113.11");
        service.recordFailure("alice", "203.0.113.12");

        assertRateLimit(() -> service.enforceAllowed("alice", "203.0.113.13"), "login temporarily locked");
    }

    @Test
    void requiresCaptchaAfterFailuresFromSameIpAcrossDifferentUsernames() {
        LoginAttemptService service =
                new LoginAttemptService(null, 10, Duration.ofMinutes(1), 5, 3, Duration.ofMinutes(15));

        service.recordFailure("alice", "203.0.113.10");
        service.recordFailure("bob", "203.0.113.10");

        assertThat(service.requiresCaptcha("carol", "203.0.113.10")).isFalse();

        service.recordFailure("carol", "203.0.113.10");

        assertThat(service.requiresCaptcha("dave", "203.0.113.10")).isTrue();
    }

    @Test
    void successClearsUsernameCaptchaButPreservesSharedIpCaptcha() {
        LoginAttemptService service =
                new LoginAttemptService(null, 10, Duration.ofMinutes(1), 5, 3, Duration.ofMinutes(15));

        service.recordFailure("alice", "203.0.113.10");
        service.recordFailure("alice", "203.0.113.10");

        assertThat(service.requiresCaptcha("alice", "203.0.113.10")).isFalse();

        service.recordFailure("alice", "203.0.113.10");

        assertThat(service.requiresCaptcha("Alice", "203.0.113.10")).isTrue();

        service.recordSuccess("alice", "203.0.113.10");

        assertThat(service.requiresCaptcha("alice", "203.0.113.11")).isFalse();
        assertThat(service.requiresCaptcha("dave", "203.0.113.10")).isTrue();
    }

    @Test
    void requiredRedisStateRejectsMissingRedisTemplate() {
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(
                        () -> new LoginAttemptService(null, true, 10, Duration.ofMinutes(1), 5, Duration.ofMinutes(15)))
                .withMessage("authentication state store unavailable");
    }

    @Test
    void requiredRedisStateConsumesRedisCountersInsteadOfLocalBuckets() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> redisValues = mockRedisValues(redisTemplate);
        when(redisValues.increment(anyString())).thenReturn(1L);

        LoginAttemptService service =
                new LoginAttemptService(redisTemplate, true, 10, Duration.ofMinutes(1), 5, Duration.ofMinutes(15));

        service.enforceAllowed("alice", "203.0.113.10");

        verify(redisValues, times(3)).increment(startsWith("login:window:"));
        verify(redisTemplate, times(3)).expire(startsWith("login:window:"), any(Duration.class));
    }

    @Test
    void requiredRedisStateRecordsCaptchaAndLockKeysForFailures() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> redisValues = mockRedisValues(redisTemplate);
        when(redisValues.increment(anyString())).thenReturn(5L);

        LoginAttemptService service =
                new LoginAttemptService(redisTemplate, true, 10, Duration.ofMinutes(1), 5, Duration.ofMinutes(15));

        service.recordFailure("alice", "203.0.113.10");

        verify(redisValues, times(3)).increment(startsWith("login:failure:"));
        verify(redisValues, times(3))
                .set(startsWith("login:captcha:"), org.mockito.ArgumentMatchers.eq("1"), any(Duration.class));
        verify(redisValues, times(3))
                .set(startsWith("login:lock:"), org.mockito.ArgumentMatchers.eq("1"), any(Duration.class));
    }

    @Test
    void requiredRedisStateFailsClosedWhenFailureStateWriteFails() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> redisValues = mockRedisValues(redisTemplate);
        when(redisValues.increment(anyString())).thenReturn(5L);
        doThrow(new RuntimeException("redis unavailable"))
                .when(redisValues)
                .set(startsWith("login:captcha:"), org.mockito.ArgumentMatchers.eq("1"), any(Duration.class));

        LoginAttemptService service =
                new LoginAttemptService(redisTemplate, true, 10, Duration.ofMinutes(1), 5, Duration.ofMinutes(15));

        assertServiceUnavailable(() -> service.recordFailure("alice", "203.0.113.10"));
    }

    @Test
    void requiredRedisStateClearsUsernameAndPairStateAfterSuccess() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        mockRedisValues(redisTemplate);

        LoginAttemptService service =
                new LoginAttemptService(redisTemplate, true, 10, Duration.ofMinutes(1), 5, Duration.ofMinutes(15));

        service.recordSuccess("alice", "203.0.113.10");

        verify(redisTemplate, times(8)).delete(anyString());
    }

    @Test
    void requiredRedisStateFailsClosedWhenClearingStateFails() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        mockRedisValues(redisTemplate);
        doThrow(new RuntimeException("redis unavailable")).when(redisTemplate).delete(anyString());

        LoginAttemptService service =
                new LoginAttemptService(redisTemplate, true, 10, Duration.ofMinutes(1), 5, Duration.ofMinutes(15));

        assertServiceUnavailable(() -> service.recordSuccess("alice", "203.0.113.10"));
    }

    @Test
    void requiredRedisStateReadsCaptchaRequirementFromRedisKeys() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        mockRedisValues(redisTemplate);
        when(redisTemplate.hasKey(anyString())).thenReturn(false, true);

        LoginAttemptService service =
                new LoginAttemptService(redisTemplate, true, 10, Duration.ofMinutes(1), 5, Duration.ofMinutes(15));

        assertThat(service.requiresCaptcha("alice", "203.0.113.10")).isTrue();
    }

    @Test
    void requiredRedisStateReturnsFalseWhenNoCaptchaKeysExist() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        mockRedisValues(redisTemplate);
        when(redisTemplate.hasKey(anyString())).thenReturn(false);

        LoginAttemptService service =
                new LoginAttemptService(redisTemplate, true, 10, Duration.ofMinutes(1), 5, Duration.ofMinutes(15));

        assertThat(service.requiresCaptcha("alice", "203.0.113.10")).isFalse();
    }

    @Test
    void requiredRedisStateFailsClosedWhenCounterStoreFails() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> redisValues = mockRedisValues(redisTemplate);
        when(redisValues.increment(anyString())).thenThrow(new RuntimeException("redis unavailable"));

        LoginAttemptService service =
                new LoginAttemptService(redisTemplate, true, 10, Duration.ofMinutes(1), 5, Duration.ofMinutes(15));

        assertServiceUnavailable(() -> service.enforceAllowed("alice", "203.0.113.10"));
    }

    @Test
    void requiredRedisStateFailsClosedWhenCaptchaLookupFails() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        mockRedisValues(redisTemplate);
        when(redisTemplate.hasKey(anyString())).thenThrow(new RuntimeException("redis unavailable"));

        LoginAttemptService service =
                new LoginAttemptService(redisTemplate, true, 10, Duration.ofMinutes(1), 5, Duration.ofMinutes(15));

        assertServiceUnavailable(() -> service.requiresCaptcha("alice", "203.0.113.10"));
    }

    private static void assertRateLimit(Runnable action, String message) {
        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(action::run)
                .withMessage(message)
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.RATE_LIMIT));
    }

    private static void assertServiceUnavailable(Runnable action) {
        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(action::run)
                .withMessage("authentication state store unavailable")
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
    }

    @SuppressWarnings("unchecked")
    private static ValueOperations<String, String> mockRedisValues(StringRedisTemplate redisTemplate) {
        ValueOperations<String, String> redisValues = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(redisValues);
        return redisValues;
    }
}
