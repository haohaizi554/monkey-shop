package com.example.monkey.user.infrastructure;

import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.user.domain.HumanVerificationService;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Service
public class TurnstileVerifier implements HumanVerificationService {

    static final int MAX_TOKEN_LENGTH = 2048;
    static final String DEFAULT_SITEVERIFY_URL = "https://challenges.cloudflare.com/turnstile/v0/siteverify";
    static final String TOKEN_REPLAY_MESSAGE = "human verification token was already used";
    static final String TOKEN_CONFIG_MESSAGE = "human verification is not configured";
    static final String TOKEN_INVALID_MESSAGE = "human verification failed";
    static final String REDIS_TOKEN_PREFIX = "captcha:turnstile:token:";

    private final SiteverifyClient siteverifyClient;
    private final StringRedisTemplate redisTemplate;
    private final boolean requireRedisState;
    private final boolean enabled;
    private final String secretKey;
    private final String expectedHostname;
    private final Duration replayTtl;
    private final Map<String, Long> consumedTokens = new ConcurrentHashMap<>();

    @Autowired
    public TurnstileVerifier(
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            @Value("${app.auth.captcha.provider:local}") String provider,
            @Value("${app.auth.captcha.turnstile.secret-key:}") String secretKey,
            @Value("${app.auth.captcha.turnstile.verify-url:" + DEFAULT_SITEVERIFY_URL + "}") String verifyUrl,
            @Value("${app.auth.captcha.turnstile.expected-hostname:}") String expectedHostname,
            @Value("${app.auth.captcha.turnstile.replay-ttl-seconds:60}") long replayTtlSeconds,
            @Value("${app.auth.require-redis-state:false}") boolean requireRedisState) {
        this(
                new RestClientSiteverifyClient(verifyUrl),
                redisTemplateProvider.getIfAvailable(),
                "turnstile".equalsIgnoreCase(provider),
                secretKey,
                expectedHostname,
                Duration.ofSeconds(replayTtlSeconds),
                requireRedisState);
    }

    TurnstileVerifier(
            SiteverifyClient siteverifyClient,
            StringRedisTemplate redisTemplate,
            boolean enabled,
            String secretKey,
            String expectedHostname,
            Duration replayTtl,
            boolean requireRedisState) {
        this.siteverifyClient = siteverifyClient;
        this.redisTemplate = redisTemplate;
        this.enabled = enabled;
        this.secretKey = secretKey;
        this.expectedHostname = expectedHostname;
        this.replayTtl = positiveDuration(replayTtl, Duration.ofSeconds(60));
        this.requireRedisState = requireRedisState;
    }

    public boolean enabled() {
        return enabled;
    }

    @Override
    public boolean verify(String token, String expectedAction, String remoteIp) {
        if (!enabled) {
            return false;
        }
        if (!StringUtils.hasText(secretKey)) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, TOKEN_CONFIG_MESSAGE);
        }
        if (!validTokenShape(token)) {
            return false;
        }
        consumeTokenOnce(token);
        SiteverifyResponse response = siteverifyClient.verify(new SiteverifyRequest(
                secretKey, token, remoteIp, UUID.randomUUID().toString()));
        return response != null
                && response.success()
                && actionMatches(response.action(), expectedAction)
                && hostnameMatches(response.hostname());
    }

    private void consumeTokenOnce(String token) {
        String key = REDIS_TOKEN_PREFIX + sha256Hex(token);
        if (requireRedisState) {
            if (!consumeRedisToken(key)) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, TOKEN_REPLAY_MESSAGE);
            }
            return;
        }
        long now = System.currentTimeMillis();
        consumedTokens.entrySet().removeIf(entry -> entry.getValue() <= now);
        Long existing = consumedTokens.putIfAbsent(key, now + replayTtl.toMillis());
        if (existing != null && existing > now) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, TOKEN_REPLAY_MESSAGE);
        }
        consumeRedisToken(key);
    }

    private boolean consumeRedisToken(String key) {
        if (redisTemplate == null) {
            return !requireRedisState;
        }
        try {
            return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key, "1", replayTtl));
        } catch (Exception exception) {
            if (requireRedisState) {
                throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, TOKEN_INVALID_MESSAGE);
            }
            return true;
        }
    }

    private boolean hostnameMatches(String hostname) {
        return !StringUtils.hasText(expectedHostname)
                || (StringUtils.hasText(hostname) && expectedHostname.equalsIgnoreCase(hostname.trim()));
    }

    private static boolean actionMatches(String action, String expectedAction) {
        return !StringUtils.hasText(expectedAction) || (StringUtils.hasText(action) && expectedAction.equals(action));
    }

    private static boolean validTokenShape(String token) {
        return StringUtils.hasText(token) && token.length() <= MAX_TOKEN_LENGTH;
    }

    private static String sha256Hex(String value) {
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

    @FunctionalInterface
    interface SiteverifyClient {
        SiteverifyResponse verify(SiteverifyRequest request);
    }

    record SiteverifyRequest(String secret, String response, String remoteip, String idempotencyKey) {}

    record SiteverifyResponse(
            boolean success,
            @JsonProperty("challenge_ts") String challengeTimestamp,
            String hostname,
            @JsonProperty("error-codes") List<String> errorCodes,
            String action,
            String cdata) {}

    private static final class RestClientSiteverifyClient implements SiteverifyClient {
        private final RestClient restClient;

        private RestClientSiteverifyClient(String verifyUrl) {
            this.restClient = RestClient.builder()
                    .baseUrl(StringUtils.hasText(verifyUrl) ? verifyUrl : DEFAULT_SITEVERIFY_URL)
                    .build();
        }

        @Override
        public SiteverifyResponse verify(SiteverifyRequest request) {
            Map<String, String> body = Map.of(
                    "secret", request.secret(),
                    "response", request.response(),
                    "remoteip", Optional.ofNullable(request.remoteip()).orElse(""),
                    "idempotency_key", request.idempotencyKey());
            try {
                return restClient
                        .post()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(SiteverifyResponse.class);
            } catch (Exception exception) {
                return new SiteverifyResponse(false, null, null, List.of("internal-error"), null, null);
            }
        }
    }
}
