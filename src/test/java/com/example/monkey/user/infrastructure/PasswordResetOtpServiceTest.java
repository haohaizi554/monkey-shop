package com.example.monkey.user.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.shared.application.tenant.TenantContext;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.user.domain.PasswordResetDeliveryService;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

class PasswordResetOtpServiceTest {

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void redisOtpCanBeConsumedByExactlyOneConcurrentResetRequest() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        mockRedisValues(redisTemplate);
        AtomicReference<String> storedHash = new AtomicReference<>(sha256Hex("654321"));
        when(redisTemplate.execute(any(RedisScript.class), anyList()))
                .thenAnswer(ignored -> storedHash.getAndSet(null));
        PasswordResetOtpService service = new PasswordResetOtpService(
                new MutableClock(),
                () -> 654321,
                () -> "email-token",
                PasswordResetDeliveryService.noop(),
                redisTemplate,
                true);

        assertThat(runConcurrentConsumers(
                        () -> service.consumeResetOtp("alice", "18888888888", "654321")))
                .containsExactlyInAnyOrder(true, false);
    }

    @Test
    void redisDualChannelChallengeCanBeConsumedByExactlyOneConcurrentResetRequest() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        mockRedisValues(redisTemplate);
        AtomicReference<String> storedHashes =
                new AtomicReference<>(sha256Hex("654321") + ":" + sha256Hex("email-token"));
        when(redisTemplate.execute(any(RedisScript.class), anyList()))
                .thenAnswer(ignored -> storedHashes.getAndSet(null));
        PasswordResetOtpService service = new PasswordResetOtpService(
                new MutableClock(),
                () -> 654321,
                () -> "email-token",
                PasswordResetDeliveryService.noop(),
                redisTemplate,
                true);

        assertThat(runConcurrentConsumers(() -> service.consumeResetChallenge(
                        "alice", "18888888888", "alice@example.com", "654321", "email-token")))
                .containsExactlyInAnyOrder(true, false);
    }

    @Test
    void redisDualChannelKeysShareOneClusterSlotAndAreTenantScoped() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        mockRedisValues(redisTemplate);
        List<List<String>> keyBatches = new ArrayList<>();
        when(redisTemplate.execute(any(RedisScript.class), anyList())).thenAnswer(invocation -> {
            keyBatches.add(List.copyOf(invocation.getArgument(1)));
            return sha256Hex("654321") + ":" + sha256Hex("email-token");
        });
        PasswordResetOtpService service = new PasswordResetOtpService(
                new MutableClock(),
                () -> 654321,
                () -> "email-token",
                PasswordResetDeliveryService.noop(),
                redisTemplate,
                true);

        TenantContext.setTenantId(1L);
        assertThat(service.consumeResetChallenge(
                        "alice", "18888888888", "alice@example.com", "654321", "email-token"))
                .isTrue();
        TenantContext.setTenantId(2L);
        assertThat(service.consumeResetChallenge(
                        "alice", "18888888888", "alice@example.com", "654321", "email-token"))
                .isTrue();

        assertThat(keyBatches).hasSize(2).allSatisfy(keys -> {
            assertThat(keys).hasSize(2);
            assertThat(redisHashTag(keys.get(0))).isNotBlank().isEqualTo(redisHashTag(keys.get(1)));
        });
        assertThat(redisHashTag(keyBatches.get(0).get(0)))
                .isNotEqualTo(redisHashTag(keyBatches.get(1).get(0)));
    }

    @Test
    void redisDualChannelStateIsStoredAtomicallyInOneClusterSlot() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        mockRedisValues(redisTemplate);
        List<List<String>> dualStoreKeyBatches = new ArrayList<>();
        List<String> dualStoreScripts = new ArrayList<>();
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenAnswer(invocation -> {
                    RedisScript<?> script = invocation.getArgument(0);
                    if (script.getScriptAsString().contains("PSETEX', KEYS[2]")) {
                        dualStoreScripts.add(script.getScriptAsString());
                        dualStoreKeyBatches.add(List.copyOf(invocation.getArgument(1)));
                    }
                    return 1L;
                });
        PasswordResetOtpService service = new PasswordResetOtpService(
                new MutableClock(),
                () -> 654321,
                () -> "email-token",
                PasswordResetDeliveryService.noop(),
                redisTemplate,
                true);

        TenantContext.setTenantId(7L);
        service.issueResetChallenge("alice", "18888888888", "alice@example.com", true);

        assertThat(dualStoreScripts)
                .singleElement()
                .satisfies(script -> assertThat(script)
                        .contains("PSETEX', KEYS[1]", "PSETEX', KEYS[2]")
                        .doesNotContain("DEL"));
        assertThat(dualStoreKeyBatches)
                .singleElement()
                .satisfies(keys -> {
                    assertThat(keys).hasSize(2);
                    assertThat(redisHashTag(keys.get(0))).isNotBlank().isEqualTo(redisHashTag(keys.get(1)));
                });
    }

    @Test
    void redisPhoneRateLimitIsOneAtomicTenantScopedClusterSlotOperation() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        mockRedisValues(redisTemplate);
        List<List<String>> keyBatches = new ArrayList<>();
        List<String> scripts = new ArrayList<>();
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenAnswer(invocation -> {
                    RedisScript<?> script = invocation.getArgument(0);
                    scripts.add(script.getScriptAsString());
                    keyBatches.add(List.copyOf(invocation.getArgument(1)));
                    return 1L;
                });
        PasswordResetOtpService service = new PasswordResetOtpService(
                new MutableClock(),
                () -> 654321,
                () -> "email-token",
                PasswordResetDeliveryService.noop(),
                redisTemplate,
                true);

        TenantContext.setTenantId(1L);
        service.issueResetOtp("alice", "18888888888", true);
        TenantContext.setTenantId(2L);
        service.issueResetOtp("alice", "18888888888", true);

        assertThat(keyBatches).hasSize(2).allSatisfy(keys -> {
            assertThat(keys).hasSize(2);
            assertThat(redisHashTag(keys.get(0))).isNotBlank().isEqualTo(redisHashTag(keys.get(1)));
            assertThat(keys).noneMatch(key -> key.contains("18888888888"));
        });
        assertThat(redisHashTag(keyBatches.get(0).get(0)))
                .isNotEqualTo(redisHashTag(keyBatches.get(1).get(0)));
        assertThat(scripts)
                .hasSize(2)
                .allSatisfy(script -> assertThat(script)
                        .contains("EXISTS", "INCR", "PEXPIRE", "PSETEX")
                        .doesNotContain("GETSET"));
    }

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
    void nonMatchingTargetsDoNotExhaustTheMatchedPhoneQuota() {
        MutableClock clock = new MutableClock();
        RecordingDelivery delivery = new RecordingDelivery();
        PasswordResetOtpService service =
                new PasswordResetOtpService(clock, () -> 654321, () -> "email-token", delivery);

        IntStream.range(0, 5).forEach(ignored -> service.issueResetChallenge("missing", "18888888888", null, false));
        service.issueResetChallenge("alice", "18888888888", null, true);

        assertThat(delivery.smsMessages).containsExactly("18888888888:654321");
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
        mockRedisValues(redisTemplate);
        RecordingDelivery delivery = new RecordingDelivery();
        PasswordResetOtpService service =
                new PasswordResetOtpService(clock, () -> 654321, () -> "email-token", delivery, redisTemplate, true);

        service.issueResetChallenge("alice", "18888888888", "alice@example.com", true);
        when(redisTemplate.execute(any(RedisScript.class), anyList()))
                .thenReturn(sha256Hex("654321") + ":" + sha256Hex("email-token"));

        assertThat(delivery.smsMessages).containsExactly("18888888888:654321");
        assertThat(delivery.emailMessages).containsExactly("alice@example.com:email-token");
        assertThat(service.consumeResetChallenge("alice", "18888888888", "alice@example.com", "654321", "email-token"))
                .isTrue();
        verify(redisTemplate, times(3)).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    void requiredRedisStateEnforcesPhoneCooldownFromRedis() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        mockRedisValues(redisTemplate);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(-1L);
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
        mockRedisValues(redisTemplate);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(-1L);
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
        mockRedisValues(redisTemplate);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(5L);
        PasswordResetOtpService service = new PasswordResetOtpService(
                new MutableClock(),
                () -> 654321,
                () -> "email-token",
                PasswordResetDeliveryService.noop(),
                redisTemplate,
                true);

        service.issueResetOtp("alice", "18888888888", true);

        verify(redisTemplate).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    void requiredRedisStateFailsClosedWhenRedisUnavailable() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> redisValues = mockRedisValues(redisTemplate);
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
        mockRedisValues(redisTemplate);
        when(redisTemplate.execute(any(RedisScript.class), anyList()))
                .thenThrow(new RuntimeException("redis unavailable"));
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

    private static List<Boolean> runConcurrentConsumers(ThrowingBooleanSupplier consumer) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> first = executor.submit(() -> consumeWhenReleased(consumer, ready, start));
            Future<Boolean> second = executor.submit(() -> consumeWhenReleased(consumer, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    private static boolean consumeWhenReleased(
            ThrowingBooleanSupplier consumer, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent reset consumers did not start");
        }
        return consumer.getAsBoolean();
    }

    @FunctionalInterface
    private interface ThrowingBooleanSupplier {
        boolean getAsBoolean() throws Exception;
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
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);
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

    private static String redisHashTag(String key) {
        int start = key.indexOf('{');
        int end = key.indexOf('}', start + 1);
        return start >= 0 && end > start + 1 ? key.substring(start + 1, end) : "";
    }
}
