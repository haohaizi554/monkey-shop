package com.example.monkey.user.application;

import com.example.monkey.shared.application.security.CaptchaChallengeResult;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.user.domain.CaptchaChallengeStore;
import com.example.monkey.user.domain.HumanVerificationService;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class CaptchaService {
    private static final Duration DEFAULT_CAPTCHA_TTL = Duration.ofMinutes(5);
    private static final String CAPTCHA_STATE_UNAVAILABLE_MESSAGE = "captcha state store unavailable";

    private final CaptchaChallengeStore challengeStore;
    private final boolean requireRedisState;
    private final Supplier<String> codeGenerator;
    private final Duration captchaTtl;
    private final boolean cookieSecure;
    private final String provider;
    private final String turnstileSiteKey;
    private final HumanVerificationService humanVerificationService;
    private final Map<String, CaptchaRecord> localChallenges = new ConcurrentHashMap<>();

    public CaptchaService() {
        this(
                CaptchaChallengeStore.unavailable(),
                false,
                CaptchaUtil::generateCode,
                DEFAULT_CAPTCHA_TTL,
                false,
                "local",
                "",
                null);
    }

    @Autowired
    public CaptchaService(
            ObjectProvider<CaptchaChallengeStore> challengeStoreProvider,
            @Value("${app.auth.captcha.ttl-seconds:300}") long captchaTtlSeconds,
            @Value("${app.auth.captcha.cookie-secure:${SESSION_COOKIE_SECURE:true}}") boolean cookieSecure,
            @Value("${app.auth.require-redis-state:false}") boolean requireRedisState,
            @Value("${app.auth.captcha.provider:local}") String provider,
            @Value("${app.auth.captcha.turnstile.site-key:}") String turnstileSiteKey,
            HumanVerificationService humanVerificationService) {
        this(
                challengeStoreProvider.getIfAvailable(CaptchaChallengeStore::unavailable),
                requireRedisState,
                CaptchaUtil::generateCode,
                Duration.ofSeconds(captchaTtlSeconds),
                cookieSecure,
                provider,
                turnstileSiteKey,
                humanVerificationService);
    }

    CaptchaService(
            CaptchaChallengeStore challengeStore,
            Supplier<String> codeGenerator,
            Duration captchaTtl,
            boolean cookieSecure) {
        this(challengeStore, false, codeGenerator, captchaTtl, cookieSecure, "local", "", null);
    }

    CaptchaService(
            CaptchaChallengeStore challengeStore,
            boolean requireRedisState,
            Supplier<String> codeGenerator,
            Duration captchaTtl,
            boolean cookieSecure) {
        this(challengeStore, requireRedisState, codeGenerator, captchaTtl, cookieSecure, "local", "", null);
    }

    CaptchaService(
            CaptchaChallengeStore challengeStore,
            boolean requireRedisState,
            Supplier<String> codeGenerator,
            Duration captchaTtl,
            boolean cookieSecure,
            String provider,
            String turnstileSiteKey,
            HumanVerificationService humanVerificationService) {
        this.challengeStore = challengeStore != null ? challengeStore : CaptchaChallengeStore.unavailable();
        this.requireRedisState = requireRedisState;
        if (requireRedisState && !this.challengeStore.available()) {
            throw new IllegalStateException(CAPTCHA_STATE_UNAVAILABLE_MESSAGE);
        }
        this.codeGenerator = codeGenerator;
        this.captchaTtl = positiveDuration(captchaTtl, DEFAULT_CAPTCHA_TTL);
        this.cookieSecure = cookieSecure;
        this.provider = StringUtils.hasText(provider) ? provider.trim().toLowerCase() : "local";
        this.turnstileSiteKey = turnstileSiteKey;
        this.humanVerificationService = humanVerificationService;
    }

    public CaptchaChallengeResult createCaptcha() throws IOException {
        if (isTurnstile()) {
            return CaptchaChallengeResult.external("turnstile", turnstileSiteKey);
        }
        String code = codeGenerator.get();
        String challengeId = newChallengeId();
        storeChallenge(challengeId, code);
        BufferedImage image = CaptchaUtil.createImage(code, 100, 40);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "JPEG", output);
        return CaptchaChallengeResult.local(challengeId, captchaTtl, cookieSecure, "image/jpeg", output.toByteArray());
    }

    public boolean validate(String challengeId, String inputCode) {
        return validate(challengeId, inputCode, null, null);
    }

    public boolean validate(String challengeId, String inputCode, String action, String remoteIp) {
        if (isTurnstile()) {
            return humanVerificationService != null && humanVerificationService.verify(inputCode, action, remoteIp);
        }
        if (!StringUtils.hasText(challengeId)) {
            return false;
        }
        String expectedCode = consumeChallenge(challengeId);
        return expectedCode != null && inputCode != null && expectedCode.equalsIgnoreCase(inputCode);
    }

    public boolean externalProviderEnabled() {
        return isTurnstile();
    }

    public String provider() {
        return provider;
    }

    public String siteKey() {
        return turnstileSiteKey;
    }

    private boolean isTurnstile() {
        return "turnstile".equals(provider);
    }

    private void storeChallenge(String challengeId, String code) {
        Instant expiresAt = Instant.now().plus(captchaTtl);
        if (!requireRedisState) {
            localChallenges.put(challengeId, new CaptchaRecord(code, expiresAt));
        }
        if (!challengeStore.available()) {
            return;
        }
        try {
            challengeStore.store(challengeId, code, captchaTtl);
        } catch (Exception e) {
            failIfRedisRequired();
        }
    }

    private String consumeChallenge(String challengeId) {
        purgeExpiredChallenges();
        if (requireRedisState) {
            return consumeStoredChallenge(challengeId);
        }
        CaptchaRecord localRecord = localChallenges.remove(challengeId);
        String storedCode = consumeStoredChallenge(challengeId);
        if (localRecord != null && Instant.now().isBefore(localRecord.expiresAt())) {
            return localRecord.code();
        }
        return storedCode;
    }

    private String consumeStoredChallenge(String challengeId) {
        if (!challengeStore.available()) {
            return null;
        }
        try {
            return challengeStore.consume(challengeId).orElse(null);
        } catch (Exception e) {
            failIfRedisRequired();
            return null;
        }
    }

    private void failIfRedisRequired() {
        if (requireRedisState) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, CAPTCHA_STATE_UNAVAILABLE_MESSAGE);
        }
    }

    private void purgeExpiredChallenges() {
        Instant now = Instant.now();
        localChallenges
                .entrySet()
                .removeIf(entry -> !now.isBefore(entry.getValue().expiresAt()));
    }

    private static String newChallengeId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static Duration positiveDuration(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }

    private record CaptchaRecord(String code, Instant expiresAt) {}
}
