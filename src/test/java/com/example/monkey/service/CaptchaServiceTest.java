package com.example.monkey.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.monkey.domain.user.CaptchaChallenge;
import com.example.monkey.domain.user.HumanVerificationService;
import com.example.monkey.shared.api.ErrorCode;
import com.example.monkey.shared.exception.BusinessException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class CaptchaServiceTest {

    @Test
    void createCaptchaReturnsLocalChallengeContent() throws Exception {
        CaptchaService captchaService = new CaptchaService(null, () -> "ABCD", Duration.ofMinutes(5), false);

        CaptchaChallenge challenge = captchaService.createCaptcha();

        assertThat(challenge.externalProvider()).isFalse();
        assertThat(challenge.provider()).isEqualTo("local");
        assertThat(challenge.challengeId()).isPresent();
        assertThat(challenge.ttl()).isEqualTo(Duration.ofMinutes(5));
        assertThat(challenge.cookieSecure()).isFalse();
        assertThat(challenge.contentType()).hasValue("image/jpeg");
        assertThat(challenge.content()).isNotEmpty();
    }

    @Test
    void invalidCaptchaTtlFallsBackToDefaultTtl() throws Exception {
        CaptchaService captchaService = new CaptchaService(null, () -> "ABCD", Duration.ZERO, true);

        CaptchaChallenge challenge = captchaService.createCaptcha();

        assertThat(challenge.ttl()).isEqualTo(Duration.ofMinutes(5));
        assertThat(challenge.cookieSecure()).isTrue();
    }

    @Test
    void successfulValidationConsumesCaptchaCode() throws Exception {
        CaptchaService captchaService = new CaptchaService(null, () -> "ABCD", Duration.ofMinutes(5), false);
        String challengeId = issuedChallengeId(captchaService);

        boolean valid = captchaService.validate(challengeId, "abcd");

        assertThat(valid).isTrue();
        assertThat(captchaService.validate(challengeId, "abcd")).isFalse();
    }

    @Test
    void failedValidationConsumesCaptchaCode() throws Exception {
        CaptchaService captchaService = new CaptchaService(null, () -> "ABCD", Duration.ofMinutes(5), false);
        String challengeId = issuedChallengeId(captchaService);

        boolean valid = captchaService.validate(challengeId, "WXYZ");

        assertThat(valid).isFalse();
        assertThat(captchaService.validate(challengeId, "ABCD")).isFalse();
    }

    @Test
    void validationRejectsMissingOrBlankCaptchaChallenge() {
        CaptchaService captchaService = new CaptchaService(null, () -> "ABCD", Duration.ofMinutes(5), false);

        assertThat(captchaService.validate(null, "ABCD")).isFalse();
        assertThat(captchaService.validate("", "ABCD")).isFalse();
        assertThat(captchaService.validate(" ", "ABCD")).isFalse();
    }

    @Test
    void expiredLocalChallengeIsRejected() throws Exception {
        CaptchaService captchaService = new CaptchaService(null, () -> "ABCD", Duration.ofMillis(1), false);
        String challengeId = issuedChallengeId(captchaService);

        Thread.sleep(5);

        assertThat(captchaService.validate(challengeId, "ABCD")).isFalse();
    }

    @Test
    void turnstileCreateCaptchaReturnsProviderMetadataOnly() throws Exception {
        HumanVerificationService verifier = mock(HumanVerificationService.class);
        CaptchaService captchaService = new CaptchaService(
                null, false, () -> "ABCD", Duration.ofMinutes(5), false, "turnstile", "site-key", verifier);

        CaptchaChallenge challenge = captchaService.createCaptcha();

        assertThat(captchaService.externalProviderEnabled()).isTrue();
        assertThat(captchaService.provider()).isEqualTo("turnstile");
        assertThat(captchaService.siteKey()).isEqualTo("site-key");
        assertThat(challenge.externalProvider()).isTrue();
        assertThat(challenge.provider()).isEqualTo("turnstile");
        assertThat(challenge.siteKey()).isEqualTo("site-key");
        assertThat(challenge.content()).isEmpty();
        verifyNoInteractions(verifier);
    }

    @Test
    void turnstileValidationDelegatesTokenActionAndRemoteIpToVerifier() {
        HumanVerificationService verifier = mock(HumanVerificationService.class);
        when(verifier.verify("token", "login", "203.0.113.7")).thenReturn(true);
        CaptchaService captchaService = new CaptchaService(
                null, false, () -> "ABCD", Duration.ofMinutes(5), false, "turnstile", "site-key", verifier);

        assertThat(captchaService.validate(null, "token", "login", "203.0.113.7"))
                .isTrue();
        verify(verifier).verify("token", "login", "203.0.113.7");
    }

    @Test
    void turnstileValidationFailsClosedWithoutVerifier() {
        CaptchaService captchaService = new CaptchaService(
                null, false, () -> "ABCD", Duration.ofMinutes(5), false, "turnstile", "site-key", null);

        assertThat(captchaService.validate(null, "token", "login", "203.0.113.7"))
                .isFalse();
    }

    @Test
    void requiredRedisStateRejectsMissingRedisTemplate() {
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> new CaptchaService(null, true, () -> "ABCD", Duration.ofMinutes(5), false))
                .withMessage("captcha state store unavailable");
    }

    @Test
    void requiredRedisStateStoresAndConsumesRedisChallenge() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> redisValues = mockRedisValues(redisTemplate);
        CaptchaService captchaService =
                new CaptchaService(redisTemplate, true, () -> "ABCD", Duration.ofMinutes(5), false);
        String challengeId = issuedChallengeId(captchaService);
        when(redisValues.get("captcha:" + challengeId)).thenReturn("ABCD");

        assertThat(captchaService.validate(challengeId, "abcd")).isTrue();
        verify(redisValues).set(startsWith("captcha:"), eq("ABCD"), eq(Duration.ofMinutes(5)));
        verify(redisTemplate).delete("captcha:" + challengeId);
    }

    @Test
    void requiredRedisStateRejectsMissingRedisChallenge() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> redisValues = mockRedisValues(redisTemplate);
        CaptchaService captchaService =
                new CaptchaService(redisTemplate, true, () -> "ABCD", Duration.ofMinutes(5), false);
        String challengeId = issuedChallengeId(captchaService);
        when(redisValues.get("captcha:" + challengeId)).thenReturn(null);

        assertThat(captchaService.validate(challengeId, "abcd")).isFalse();
        verify(redisTemplate).delete("captcha:" + challengeId);
    }

    @Test
    void optionalRedisStoreFailureStillUsesLocalChallenge() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> redisValues = mockRedisValues(redisTemplate);
        doThrow(new RuntimeException("redis unavailable"))
                .when(redisValues)
                .set(startsWith("captcha:"), eq("ABCD"), any(Duration.class));
        CaptchaService captchaService =
                new CaptchaService(redisTemplate, false, () -> "ABCD", Duration.ofMinutes(5), false);

        String challengeId = issuedChallengeId(captchaService);

        assertThat(captchaService.validate(challengeId, "ABCD")).isTrue();
    }

    @Test
    void requiredRedisStateFailsClosedWhenStoreFails() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> redisValues = mockRedisValues(redisTemplate);
        doThrow(new RuntimeException("redis unavailable"))
                .when(redisValues)
                .set(startsWith("captcha:"), eq("ABCD"), any(Duration.class));
        CaptchaService captchaService =
                new CaptchaService(redisTemplate, true, () -> "ABCD", Duration.ofMinutes(5), false);

        assertServiceUnavailable(captchaService::createCaptcha);
    }

    @Test
    void requiredRedisStateFailsClosedWhenValidationLookupFails() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> redisValues = mockRedisValues(redisTemplate);
        CaptchaService captchaService =
                new CaptchaService(redisTemplate, true, () -> "ABCD", Duration.ofMinutes(5), false);
        String challengeId = issuedChallengeId(captchaService);
        when(redisValues.get("captcha:" + challengeId)).thenThrow(new RuntimeException("redis unavailable"));

        assertServiceUnavailable(() -> captchaService.validate(challengeId, "ABCD"));
    }

    private static String issuedChallengeId(CaptchaService captchaService) throws Exception {
        return captchaService.createCaptcha().challengeId().orElseThrow();
    }

    private static void assertServiceUnavailable(ThrowingAction action) {
        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(action::run)
                .withMessage("captcha state store unavailable")
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
    }

    @SuppressWarnings("unchecked")
    private static ValueOperations<String, String> mockRedisValues(StringRedisTemplate redisTemplate) {
        ValueOperations<String, String> redisValues = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(redisValues);
        return redisValues;
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}
