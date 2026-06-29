package com.example.monkey.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.domain.user.PasswordResetDeliveryService;
import com.example.monkey.shared.api.ErrorCode;
import com.example.monkey.shared.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class PasswordResetOtpServiceTest {

    @Test
    void issuedOtpCanBeConsumedOnceBeforeExpiration() {
        MutableClock clock = new MutableClock();
        RecordingDelivery delivery = new RecordingDelivery();
        PasswordResetOtpService service =
                new PasswordResetOtpService(clock, () -> 654321, () -> "email-token", delivery);

        service.issueResetOtp("alice", "18888888888", true);

        assertThat(delivery.smsMessages).containsExactly("18888888888:654321");
        assertThat(service.consumeResetOtp("alice", "18888888888", "654321")).isTrue();
        assertThat(service.consumeResetOtp("alice", "18888888888", "654321")).isFalse();
    }

    @Test
    void wrongOtpIsRejectedAndConsumed() {
        MutableClock clock = new MutableClock();
        PasswordResetOtpService service = new PasswordResetOtpService(clock, () -> 654321);

        service.issueResetOtp("alice", "18888888888", true);

        assertThat(service.consumeResetOtp("alice", "18888888888", "111111")).isFalse();
        assertThat(service.consumeResetOtp("alice", "18888888888", "654321")).isFalse();
    }

    @Test
    void defaultConstructorCanIssueChallengesWithSecureGenerators() {
        PasswordResetOtpService service = new PasswordResetOtpService();

        service.issueResetChallenge("alice", "18888888888", "alice@example.com", true);

        assertThat(service.consumeResetOtp("alice", "18888888888", "000000")).isFalse();
    }

    @Test
    void invalidOtpInputsAreRejectedWithoutStateLookup() {
        PasswordResetOtpService service = new PasswordResetOtpService(new MutableClock(), () -> 654321);

        assertThat(service.consumeResetOtp("", "18888888888", "654321")).isFalse();
        assertThat(service.consumeResetOtp("alice", " ", "654321")).isFalse();
        assertThat(service.consumeResetOtp("alice", "18888888888", " ")).isFalse();
    }

    @Test
    void emailTokenChannelMustMatchAlongsideSmsOtpWhenEmailIsPresent() {
        MutableClock clock = new MutableClock();
        RecordingDelivery delivery = new RecordingDelivery();
        PasswordResetOtpService service =
                new PasswordResetOtpService(clock, () -> 654321, () -> "email-token", delivery);

        service.issueResetChallenge("alice", "18888888888", "alice@example.com", true);

        assertThat(delivery.smsMessages).containsExactly("18888888888:654321");
        assertThat(delivery.emailMessages).containsExactly("alice@example.com:email-token");
        assertThat(service.consumeResetChallenge("alice", "18888888888", "alice@example.com", "654321", "email-token"))
                .isTrue();
        assertThat(service.consumeResetChallenge("alice", "18888888888", "alice@example.com", "654321", "email-token"))
                .isFalse();
    }

    @Test
    void wrongEmailTokenRejectsDualChannelReset() {
        MutableClock clock = new MutableClock();
        PasswordResetOtpService service = new PasswordResetOtpService(
                clock, () -> 654321, () -> "email-token", PasswordResetDeliveryService.noop());

        service.issueResetChallenge("alice", "18888888888", "alice@example.com", true);

        assertThat(service.consumeResetChallenge("alice", "18888888888", "alice@example.com", "654321", "wrong-token"))
                .isFalse();
    }

    @Test
    void missingDualChannelInputsAreRejected() {
        MutableClock clock = new MutableClock();
        PasswordResetOtpService service = new PasswordResetOtpService(
                clock, () -> 654321, () -> "email-token", PasswordResetDeliveryService.noop());

        service.issueResetChallenge("alice", "18888888888", "alice@example.com", true);

        assertThat(service.consumeResetChallenge("alice", "18888888888", "alice@example.com", "654321", " "))
                .isFalse();
        assertThat(service.consumeResetChallenge("alice", "18888888888", "alice@example.com", " ", "email-token"))
                .isFalse();
    }

    @Test
    void blankEmailFallsBackToSingleChannelOtp() {
        MutableClock clock = new MutableClock();
        PasswordResetOtpService service = new PasswordResetOtpService(
                clock, () -> 654321, () -> "email-token", PasswordResetDeliveryService.noop());

        service.issueResetChallenge("alice", "18888888888", " ", true);

        assertThat(service.consumeResetChallenge("alice", "18888888888", " ", "654321", ""))
                .isTrue();
    }

    @Test
    void blankPhoneDoesNotIssueResetChallenge() {
        MutableClock clock = new MutableClock();
        RecordingDelivery delivery = new RecordingDelivery();
        PasswordResetOtpService service =
                new PasswordResetOtpService(clock, () -> 654321, () -> "email-token", delivery);

        service.issueResetChallenge("alice", " ", "alice@example.com", true);

        assertThat(delivery.smsMessages).isEmpty();
        assertThat(delivery.emailMessages).isEmpty();
    }

    @Test
    void nonMatchingTargetDoesNotDeliverEitherResetChannel() {
        MutableClock clock = new MutableClock();
        RecordingDelivery delivery = new RecordingDelivery();
        PasswordResetOtpService service =
                new PasswordResetOtpService(clock, () -> 654321, () -> "email-token", delivery);

        service.issueResetChallenge("alice", "18888888888", "alice@example.com", false);

        assertThat(delivery.smsMessages).isEmpty();
        assertThat(delivery.emailMessages).isEmpty();
    }

    @Test
    void deliveryFailureDoesNotStoreConsumableOtp() {
        MutableClock clock = new MutableClock();
        PasswordResetOtpService service =
                new PasswordResetOtpService(clock, () -> 654321, () -> "email-token", new FailingDelivery());

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> service.issueResetOtp("alice", "18888888888", true))
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
        assertThat(service.consumeResetOtp("alice", "18888888888", "654321")).isFalse();
    }

    @Test
    void expiredOtpCannotBeConsumed() {
        MutableClock clock = new MutableClock();
        PasswordResetOtpService service = new PasswordResetOtpService(clock, () -> 654321);

        service.issueResetOtp("alice", "18888888888", true);
        clock.advance(Duration.ofMinutes(6));

        assertThat(service.consumeResetOtp("alice", "18888888888", "654321")).isFalse();
    }

    @Test
    void phoneIsLimitedToOneResetOtpPerMinute() {
        MutableClock clock = new MutableClock();
        PasswordResetOtpService service = new PasswordResetOtpService(clock, () -> 654321);

        service.issueResetOtp("alice", "18888888888", true);
        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> service.issueResetOtp("alice", "18888888888", true))
                .withMessage("too many reset requests")
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.RATE_LIMIT));

        clock.advance(Duration.ofSeconds(61));

        service.issueResetOtp("alice", "18888888888", true);
    }

    @Test
    void phoneIsLimitedToFiveResetOtpsPerDay() {
        MutableClock clock = new MutableClock();
        PasswordResetOtpService service = new PasswordResetOtpService(clock, () -> 654321);

        for (int i = 0; i < 5; i++) {
            service.issueResetOtp("alice", "18888888888", true);
            clock.advance(Duration.ofMinutes(2));
        }

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> service.issueResetOtp("alice", "18888888888", true))
                .withMessage("too many reset requests")
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.RATE_LIMIT));
    }

    @Test
    void dailyPhoneWindowExpiresOldEntries() {
        MutableClock clock = new MutableClock();
        PasswordResetOtpService service = new PasswordResetOtpService(clock, () -> 654321);

        for (int i = 0; i < 5; i++) {
            service.issueResetOtp("alice", "18888888888", true);
            clock.advance(Duration.ofMinutes(2));
        }
        clock.advance(Duration.ofDays(1));

        service.issueResetOtp("alice", "18888888888", true);
    }

    @Test
    void requiredRedisStateRejectsMissingRedisTemplate() {
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> new PasswordResetOtpService(
                        new MutableClock(),
                        () -> 654321,
                        () -> "email-token",
                        PasswordResetDeliveryService.noop(),
                        null,
                        true))
                .withMessage("password reset state store unavailable");
    }

    @Test
    void requiredRedisStateStoresAndConsumesDualChannelChallenge() {
        MutableClock clock = new MutableClock();
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> redisValues = mockRedisValues(redisTemplate);
        when(redisTemplate.hasKey(startsWith("password-reset:cooldown:"))).thenReturn(false);
        when(redisValues.increment(startsWith("password-reset:daily:"))).thenReturn(1L);
        RecordingDelivery delivery = new RecordingDelivery();
        PasswordResetOtpService service =
                new PasswordResetOtpService(clock, () -> 654321, () -> "email-token", delivery, redisTemplate, true);

        service.issueResetChallenge("alice", "18888888888", "alice@example.com", true);
        when(redisValues.get(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            if (key.startsWith("password-reset:otp:")) {
                return sha256Hex("654321");
            }
            if (key.startsWith("password-reset:email:")) {
                return sha256Hex("email-token");
            }
            return null;
        });

        assertThat(delivery.smsMessages).containsExactly("18888888888:654321");
        assertThat(delivery.emailMessages).containsExactly("alice@example.com:email-token");
        assertThat(service.consumeResetChallenge("alice", "18888888888", "alice@example.com", "654321", "email-token"))
                .isTrue();
        verify(redisValues).set(startsWith("password-reset:otp:"), eq(sha256Hex("654321")), eq(Duration.ofMinutes(5)));
        verify(redisValues)
                .set(startsWith("password-reset:email:"), eq(sha256Hex("email-token")), eq(Duration.ofMinutes(5)));
        verify(redisValues).set(startsWith("password-reset:cooldown:"), eq("1"), eq(Duration.ofMinutes(1)));
        verify(redisTemplate).delete(startsWith("password-reset:otp:"));
        verify(redisTemplate).delete(startsWith("password-reset:email:"));
    }

    @Test
    void requiredRedisStateEnforcesPhoneCooldownFromRedis() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        mockRedisValues(redisTemplate);
        when(redisTemplate.hasKey(startsWith("password-reset:cooldown:"))).thenReturn(true);
        PasswordResetOtpService service = new PasswordResetOtpService(
                new MutableClock(),
                () -> 654321,
                () -> "email-token",
                PasswordResetDeliveryService.noop(),
                redisTemplate,
                true);

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> service.issueResetOtp("alice", "18888888888", true))
                .withMessage("too many reset requests")
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.RATE_LIMIT));
    }

    @Test
    void requiredRedisStateLimitsDailyCountFromRedis() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> redisValues = mockRedisValues(redisTemplate);
        when(redisTemplate.hasKey(startsWith("password-reset:cooldown:"))).thenReturn(false);
        when(redisValues.increment(startsWith("password-reset:daily:"))).thenReturn(6L);
        PasswordResetOtpService service = new PasswordResetOtpService(
                new MutableClock(),
                () -> 654321,
                () -> "email-token",
                PasswordResetDeliveryService.noop(),
                redisTemplate,
                true);

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> service.issueResetOtp("alice", "18888888888", true))
                .withMessage("too many reset requests")
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.RATE_LIMIT));
    }

    @Test
    void requiredRedisStateAllowsFifthDailyResetRequestBoundary() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> redisValues = mockRedisValues(redisTemplate);
        when(redisTemplate.hasKey(startsWith("password-reset:cooldown:"))).thenReturn(false);
        when(redisValues.increment(startsWith("password-reset:daily:"))).thenReturn(5L);
        PasswordResetOtpService service = new PasswordResetOtpService(
                new MutableClock(),
                () -> 654321,
                () -> "email-token",
                PasswordResetDeliveryService.noop(),
                redisTemplate,
                true);

        service.issueResetOtp("alice", "18888888888", true);

        verify(redisValues).set(startsWith("password-reset:cooldown:"), eq("1"), eq(Duration.ofMinutes(1)));
    }

    @Test
    void requiredRedisStateFailsClosedWhenRedisUnavailable() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> redisValues = mockRedisValues(redisTemplate);
        when(redisTemplate.hasKey(startsWith("password-reset:cooldown:"))).thenReturn(false);
        when(redisValues.increment(startsWith("password-reset:daily:"))).thenReturn(1L);
        doThrow(new RuntimeException("redis unavailable"))
                .when(redisValues)
                .set(startsWith("password-reset:otp:"), eq(sha256Hex("654321")), eq(Duration.ofMinutes(5)));
        PasswordResetOtpService service = new PasswordResetOtpService(
                new MutableClock(),
                () -> 654321,
                () -> "email-token",
                PasswordResetDeliveryService.noop(),
                redisTemplate,
                true);

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> service.issueResetOtp("alice", "18888888888", true))
                .withMessage("password reset state store unavailable")
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
    }

    @Test
    void requiredRedisStateFailsClosedWhenConsumingStoredOtpFails() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> redisValues = mockRedisValues(redisTemplate);
        when(redisValues.get(startsWith("password-reset:otp:"))).thenThrow(new RuntimeException("redis unavailable"));
        PasswordResetOtpService service = new PasswordResetOtpService(
                new MutableClock(),
                () -> 654321,
                () -> "email-token",
                PasswordResetDeliveryService.noop(),
                redisTemplate,
                true);

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> service.consumeResetOtp("alice", "18888888888", "654321"))
                .withMessage("password reset state store unavailable")
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
    }

    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-06-28T00:00:00Z");

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
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

    private static final class RecordingDelivery implements PasswordResetDeliveryService {

        private final List<String> smsMessages = new ArrayList<>();
        private final List<String> emailMessages = new ArrayList<>();

        @Override
        public void sendSmsOtp(String phone, String code) {
            smsMessages.add(phone + ":" + code);
        }

        @Override
        public void sendEmailToken(String email, String token) {
            emailMessages.add(email + ":" + token);
        }
    }

    private static final class FailingDelivery implements PasswordResetDeliveryService {

        @Override
        public void sendSmsOtp(String phone, String code) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "delivery unavailable");
        }

        @Override
        public void sendEmailToken(String email, String token) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "delivery unavailable");
        }
    }

    @SuppressWarnings("unchecked")
    private static ValueOperations<String, String> mockRedisValues(StringRedisTemplate redisTemplate) {
        ValueOperations<String, String> redisValues = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(redisValues);
        return redisValues;
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest is unavailable", e);
        }
    }
}
