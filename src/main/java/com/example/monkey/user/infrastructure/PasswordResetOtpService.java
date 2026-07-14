package com.example.monkey.user.infrastructure;

import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.user.domain.PasswordResetChallengeService;
import com.example.monkey.user.domain.PasswordResetDeliveryService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class PasswordResetOtpService implements PasswordResetChallengeService {

    private static final String RESET_RATE_LIMIT_MESSAGE = "too many reset requests";
    private static final String RESET_STATE_UNAVAILABLE_MESSAGE = "password reset state store unavailable";

    private static final Duration OTP_TTL = Duration.ofMinutes(5);
    private static final Duration EMAIL_TOKEN_TTL = Duration.ofMinutes(5);
    private static final Duration PHONE_COOLDOWN = Duration.ofMinutes(1);
    private static final Duration DAILY_WINDOW = Duration.ofDays(1);
    private static final int DAILY_PHONE_LIMIT = 5;
    private static final int EMAIL_TOKEN_BYTES = 32;
    private static final String REDIS_OTP_PREFIX = "password-reset:otp:";
    private static final String REDIS_EMAIL_TOKEN_PREFIX = "password-reset:email:";
    private static final String REDIS_COOLDOWN_PREFIX = "password-reset:cooldown:";
    private static final String REDIS_DAILY_PREFIX = "password-reset:daily:";

    private final Clock clock;
    private final IntSupplier codeGenerator;
    private final Supplier<String> emailTokenGenerator;
    private final PasswordResetDeliveryService deliveryService;
    private final StringRedisTemplate redisTemplate;
    private final boolean requireRedisState;
    private final Map<String, OtpRecord> activeOtps = new ConcurrentHashMap<>();
    private final Map<String, EmailTokenRecord> activeEmailTokens = new ConcurrentHashMap<>();
    private final Map<String, PhoneIssueHistory> phoneHistory = new ConcurrentHashMap<>();

    public PasswordResetOtpService() {
        SecureRandom secureRandom = new SecureRandom();
        this.clock = Clock.systemUTC();
        this.codeGenerator = () -> secureRandom.nextInt(1_000_000);
        this.emailTokenGenerator = () -> newEmailToken(secureRandom);
        this.deliveryService = PasswordResetDeliveryService.noop();
        this.redisTemplate = null;
        this.requireRedisState = false;
    }

    @Autowired
    public PasswordResetOtpService(
            PasswordResetDeliveryService deliveryService,
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            @Value("${app.auth.require-redis-state:false}") boolean requireRedisState) {
        this(
                Clock.systemUTC(),
                secureCodeGenerator(),
                secureEmailTokenGenerator(),
                deliveryService,
                redisTemplateProvider.getIfAvailable(),
                requireRedisState);
    }

    PasswordResetOtpService(Clock clock, IntSupplier codeGenerator) {
        this(clock, codeGenerator, secureEmailTokenGenerator(), PasswordResetDeliveryService.noop());
    }

    PasswordResetOtpService(
            Clock clock,
            IntSupplier codeGenerator,
            Supplier<String> emailTokenGenerator,
            PasswordResetDeliveryService deliveryService) {
        this(clock, codeGenerator, emailTokenGenerator, deliveryService, null, false);
    }

    PasswordResetOtpService(
            Clock clock,
            IntSupplier codeGenerator,
            Supplier<String> emailTokenGenerator,
            PasswordResetDeliveryService deliveryService,
            StringRedisTemplate redisTemplate,
            boolean requireRedisState) {
        this.clock = clock;
        this.codeGenerator = codeGenerator;
        this.emailTokenGenerator = emailTokenGenerator;
        this.deliveryService = deliveryService;
        this.redisTemplate = redisTemplate;
        this.requireRedisState = requireRedisState;
        if (requireRedisState && redisTemplate == null) {
            throw new IllegalStateException(RESET_STATE_UNAVAILABLE_MESSAGE);
        }
    }

    @Override
    public void issueResetOtp(String username, String phone, boolean targetMatches) {
        issueResetChallenge(username, phone, null, targetMatches);
    }

    @Override
    public void issueResetChallenge(String username, String phone, String email, boolean targetMatches) {
        String normalizedPhone = normalize(phone);
        if (!StringUtils.hasText(normalizedPhone)) {
            return;
        }

        Instant now = clock.instant();
        if (targetMatches && StringUtils.hasText(username)) {
            enforcePhoneLimit(normalizedPhone, now);
            String code = newCode();
            String normalizedEmail = normalize(email).toLowerCase();
            String emailToken = StringUtils.hasText(normalizedEmail) ? emailTokenGenerator.get() : null;

            deliveryService.sendSmsOtp(normalizedPhone, code);
            if (StringUtils.hasText(normalizedEmail)) {
                deliveryService.sendEmailToken(normalizedEmail, emailToken);
            }

            storeOtp(resetKey(username, normalizedPhone), code, now.plus(OTP_TTL));
            if (StringUtils.hasText(normalizedEmail)) {
                storeEmailToken(
                        resetKey(username, normalizedPhone, normalizedEmail), emailToken, now.plus(EMAIL_TOKEN_TTL));
            }
        }
    }

    @Override
    public boolean consumeResetOtp(String username, String phone, String otp) {
        String normalizedPhone = normalize(phone);
        if (!StringUtils.hasText(username) || !StringUtils.hasText(normalizedPhone) || !StringUtils.hasText(otp)) {
            return false;
        }

        OtpRecord record = consumeOtpRecord(resetKey(username, normalizedPhone));
        if (record == null || clock.instant().isAfter(record.expiresAt())) {
            return false;
        }
        return record.codeHash().equals(sha256Hex(otp.trim()));
    }

    @Override
    public boolean consumeResetChallenge(String username, String phone, String email, String otp, String emailToken) {
        String normalizedPhone = normalize(phone);
        String normalizedEmail = normalize(email).toLowerCase();
        if (!StringUtils.hasText(normalizedEmail)) {
            return consumeResetOtp(username, normalizedPhone, otp);
        }
        if (!StringUtils.hasText(username)
                || !StringUtils.hasText(normalizedPhone)
                || !StringUtils.hasText(otp)
                || !StringUtils.hasText(emailToken)) {
            return false;
        }

        Instant now = clock.instant();
        OtpRecord otpRecord = consumeOtpRecord(resetKey(username, normalizedPhone));
        EmailTokenRecord emailRecord = consumeEmailTokenRecord(resetKey(username, normalizedPhone, normalizedEmail));
        return otpRecord != null
                && emailRecord != null
                && !now.isAfter(otpRecord.expiresAt())
                && !now.isAfter(emailRecord.expiresAt())
                && otpRecord.codeHash().equals(sha256Hex(otp.trim()))
                && emailRecord.tokenHash().equals(sha256Hex(emailToken.trim()));
    }

    private void enforcePhoneLimit(String normalizedPhone, Instant now) {
        if (requireRedisState) {
            enforceRedisPhoneLimit(normalizedPhone);
            return;
        }
        PhoneIssueHistory history = phoneHistory.computeIfAbsent(normalizedPhone, ignored -> new PhoneIssueHistory());
        synchronized (history) {
            history.removeExpired(now.minus(DAILY_WINDOW));
            if (history.isLimited(now)) {
                throw new BusinessException(ErrorCode.RATE_LIMIT, RESET_RATE_LIMIT_MESSAGE);
            }
            history.record(now);
        }
    }

    private void enforceRedisPhoneLimit(String normalizedPhone) {
        String phoneKey = phoneKey(normalizedPhone);
        try {
            if (Boolean.TRUE.equals(redisTemplate.hasKey(REDIS_COOLDOWN_PREFIX + phoneKey))) {
                throw resetRateLimited();
            }
            Long dailyCount = redisTemplate.opsForValue().increment(REDIS_DAILY_PREFIX + phoneKey);
            if (dailyCount != null && dailyCount == 1L) {
                redisTemplate.expire(REDIS_DAILY_PREFIX + phoneKey, DAILY_WINDOW);
            }
            if (dailyCount == null || dailyCount > DAILY_PHONE_LIMIT) {
                throw resetRateLimited();
            }
            redisTemplate.opsForValue().set(REDIS_COOLDOWN_PREFIX + phoneKey, "1", PHONE_COOLDOWN);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw resetStateUnavailable();
        }
    }

    private void storeOtp(String key, String code, Instant expiresAt) {
        if (!requireRedisState) {
            activeOtps.put(key, new OtpRecord(sha256Hex(code), expiresAt));
            return;
        }
        try {
            redisTemplate.opsForValue().set(REDIS_OTP_PREFIX + redisKey(key), sha256Hex(code), OTP_TTL);
        } catch (Exception e) {
            throw resetStateUnavailable();
        }
    }

    private void storeEmailToken(String key, String emailToken, Instant expiresAt) {
        if (!requireRedisState) {
            activeEmailTokens.put(key, new EmailTokenRecord(sha256Hex(emailToken), expiresAt));
            return;
        }
        try {
            redisTemplate
                    .opsForValue()
                    .set(REDIS_EMAIL_TOKEN_PREFIX + redisKey(key), sha256Hex(emailToken), EMAIL_TOKEN_TTL);
        } catch (Exception e) {
            throw resetStateUnavailable();
        }
    }

    private OtpRecord consumeOtpRecord(String key) {
        if (!requireRedisState) {
            return activeOtps.remove(key);
        }
        try {
            String redisKey = REDIS_OTP_PREFIX + redisKey(key);
            String codeHash = redisTemplate.opsForValue().get(redisKey);
            redisTemplate.delete(redisKey);
            return StringUtils.hasText(codeHash)
                    ? new OtpRecord(codeHash, clock.instant().plus(OTP_TTL))
                    : null;
        } catch (Exception e) {
            throw resetStateUnavailable();
        }
    }

    private EmailTokenRecord consumeEmailTokenRecord(String key) {
        if (!requireRedisState) {
            return activeEmailTokens.remove(key);
        }
        try {
            String redisKey = REDIS_EMAIL_TOKEN_PREFIX + redisKey(key);
            String tokenHash = redisTemplate.opsForValue().get(redisKey);
            redisTemplate.delete(redisKey);
            return StringUtils.hasText(tokenHash)
                    ? new EmailTokenRecord(tokenHash, clock.instant().plus(EMAIL_TOKEN_TTL))
                    : null;
        } catch (Exception e) {
            throw resetStateUnavailable();
        }
    }

    private String newCode() {
        return String.format("%06d", Math.floorMod(codeGenerator.getAsInt(), 1_000_000));
    }

    private static String newEmailToken(SecureRandom secureRandom) {
        byte[] tokenBytes = new byte[EMAIL_TOKEN_BYTES];
        secureRandom.nextBytes(tokenBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
    }

    private static Supplier<String> secureEmailTokenGenerator() {
        SecureRandom secureRandom = new SecureRandom();
        return () -> newEmailToken(secureRandom);
    }

    private static IntSupplier secureCodeGenerator() {
        SecureRandom secureRandom = new SecureRandom();
        return () -> secureRandom.nextInt(1_000_000);
    }

    private static String resetKey(String username, String phone) {
        return normalize(username).toLowerCase() + ":" + normalize(phone);
    }

    private static String resetKey(String username, String phone, String email) {
        return resetKey(username, phone) + ":" + normalize(email).toLowerCase();
    }

    private static String redisKey(String value) {
        return sha256Hex("reset|" + normalize(value).toLowerCase());
    }

    private static String phoneKey(String value) {
        return sha256Hex("phone|" + normalize(value));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest is unavailable", e);
        }
    }

    private static BusinessException resetRateLimited() {
        return new BusinessException(ErrorCode.RATE_LIMIT, RESET_RATE_LIMIT_MESSAGE);
    }

    private static BusinessException resetStateUnavailable() {
        return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, RESET_STATE_UNAVAILABLE_MESSAGE);
    }

    private record OtpRecord(String codeHash, Instant expiresAt) {}

    private record EmailTokenRecord(String tokenHash, Instant expiresAt) {}

    private static final class PhoneIssueHistory {
        private final ArrayDeque<Instant> issuedAt = new ArrayDeque<>();
        private Instant lastIssuedAt;

        void record(Instant now) {
            issuedAt.addLast(now);
            lastIssuedAt = now;
        }

        boolean isLimited(Instant now) {
            return issuedAt.size() >= DAILY_PHONE_LIMIT
                    || (lastIssuedAt != null && now.isBefore(lastIssuedAt.plus(PHONE_COOLDOWN)));
        }

        void removeExpired(Instant cutoff) {
            while (!issuedAt.isEmpty() && issuedAt.peekFirst().isBefore(cutoff)) {
                issuedAt.removeFirst();
            }
        }
    }
}
