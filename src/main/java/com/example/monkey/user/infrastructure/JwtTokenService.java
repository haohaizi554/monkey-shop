package com.example.monkey.user.infrastructure;

import com.example.monkey.shared.application.security.SessionTokenPair;
import com.example.monkey.shared.domain.security.JwtTokenPair;
import com.example.monkey.shared.interfaces.web.SessionTokenTransport;
import com.example.monkey.user.domain.RefreshTokenReuseException;
import com.example.monkey.user.domain.SessionTokenService;
import com.example.monkey.user.domain.SessionTokenService.AuthenticatedAccessToken;
import com.example.monkey.user.domain.SessionTokenService.AuthenticatedRefreshToken;
import com.example.monkey.user.domain.SessionTokenService.RecoveredRefreshToken;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.util.StringUtils;

public class JwtTokenService implements SessionTokenService, SessionTokenTransport {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenService.class);
    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_AUTHORITIES = "auth";
    private static final String CLAIM_TENANT_ID = "tenant_id";
    private static final String CLAIM_TOKEN_TYPE = "typ";
    private static final String TOKEN_TYPE_ACCESS = "access";
    private static final String TOKEN_TYPE_REFRESH = "refresh";
    private static final long DEFAULT_TENANT_ID = 1L;
    private static final String JWT_ISSUER = "monkeyshop";
    private static final String JWT_AUDIENCE = "monkeyshop-web";
    private static final String COOKIE_ACCESS_TOKEN = "access_token";
    private static final String COOKIE_REFRESH_TOKEN = "refresh_token";
    private static final String SECURITY_PREFIX = "Bearer ";
    private static final String REDIS_REFRESH_TOKEN_PREFIX = "jwt:refresh:";
    private static final String REDIS_REFRESH_ROTATION_PREFIX = "jwt:refresh:rotation:";
    private static final String REDIS_REVOKED_ACCESS_PREFIX = "jwt:revoked:access:";
    private static final String REDIS_REVOKED_USER_PREFIX = "jwt:revoked:user:";
    private static final int MIN_HMAC_SECRET_BYTES = 32;
    private static final Duration DEFAULT_REFRESH_ROTATION_GRACE = Duration.ofSeconds(5);
    private static final String ROTATION_STATUS_ROTATED = "ROTATED";
    private static final String ROTATION_STATUS_REUSED = "REUSED";
    private static final String ROTATION_STATUS_REVOKED = "REVOKED";
    private static final String ROTATION_CIPHERTEXT_VERSION = "v1";
    private static final String JWT_PARSE_FAILURE_METRIC = "auth.jwt.parse.failure";
    private static final String JWT_PARSE_REASON_MALFORMED = "malformed";
    private static final String JWT_PARSE_REASON_INVALID_SIGNATURE = "invalid-signature";
    private static final String JWT_PARSE_REASON_EXPIRED = "expired";
    private static final String JWT_PARSE_REASON_REVOKED = "revoked";
    private static final String JWT_PARSE_REASON_UNEXPECTED = "unexpected";
    private static final String ROTATION_KEY_DERIVATION_SALT = "MonkeyShop JWT rotation recovery HKDF salt v1";
    private static final String ROTATION_KEY_DERIVATION_INFO = "MonkeyShop JWT rotation recovery AES-256-GCM key v1";
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final DefaultRedisScript<String> ROTATE_REFRESH_TOKEN = new DefaultRedisScript<>("""
            local revokedGeneration = redis.call('get', KEYS[4])
            if revokedGeneration and tonumber(ARGV[1]) <= tonumber(revokedGeneration) then
                return 'REVOKED'
            end
            local recovered = redis.call('get', KEYS[3])
            if recovered then
                return recovered
            end
            local active = redis.call('get', KEYS[1])
            if not active then
                return 'REUSED'
            end
            redis.call('del', KEYS[1])
            redis.call('psetex', KEYS[2], ARGV[2], '1')
            redis.call('psetex', KEYS[3], ARGV[3], ARGV[4])
            return 'ROTATED'
            """, String.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final byte[] signingSecret;
    private final byte[] recoveryEncryptionKey;
    private final long accessTokenTtlSeconds;
    private final long refreshTokenTtlSeconds;
    private final long accessCookieMaxAgeSeconds;
    private final long refreshCookieMaxAgeSeconds;
    private final boolean cookieSecure;
    private final StringRedisTemplate redisTemplate;
    private final boolean requireRedisTokenStore;
    private final Duration refreshRotationGrace;
    private final Clock clock;
    private final MeterRegistry meterRegistry;
    private final Map<String, Instant> refreshTokens = new ConcurrentHashMap<>();
    private final Map<String, RefreshTokenRotation> refreshTokenRotations = new ConcurrentHashMap<>();
    private final Map<String, Instant> revokedAccessTokens = new ConcurrentHashMap<>();
    private final Map<Long, Instant> revokedUserTokensIssuedBefore = new ConcurrentHashMap<>();
    private final Object refreshRotationMonitor = new Object();

    public JwtTokenService(
            @Value("${app.jwt.secret:}") String rawSecret,
            @Value("${app.jwt.access-token-ttl-seconds:900}") long accessTokenTtlSeconds,
            @Value("${app.jwt.refresh-token-ttl-seconds:604800}") long refreshTokenTtlSeconds,
            @Value("${app.jwt.access-cookie-max-age-seconds:900}") long accessCookieMaxAgeSeconds,
            @Value("${app.jwt.refresh-cookie-max-age-seconds:604800}") long refreshCookieMaxAgeSeconds,
            @Value("${app.jwt.cookie-secure:${SESSION_COOKIE_SECURE:true}}") boolean cookieSecure,
            @Value("${app.jwt.allow-generated-secret:false}") boolean allowGeneratedSecret) {
        this(
                rawSecret,
                accessTokenTtlSeconds,
                refreshTokenTtlSeconds,
                accessCookieMaxAgeSeconds,
                refreshCookieMaxAgeSeconds,
                cookieSecure,
                allowGeneratedSecret,
                false,
                null,
                DEFAULT_REFRESH_ROTATION_GRACE,
                Clock.systemUTC());
    }

    public JwtTokenService(
            String rawSecret,
            long accessTokenTtlSeconds,
            long refreshTokenTtlSeconds,
            long accessCookieMaxAgeSeconds,
            long refreshCookieMaxAgeSeconds,
            boolean cookieSecure,
            StringRedisTemplate redisTemplate) {
        this(
                rawSecret,
                accessTokenTtlSeconds,
                refreshTokenTtlSeconds,
                accessCookieMaxAgeSeconds,
                refreshCookieMaxAgeSeconds,
                cookieSecure,
                false,
                false,
                redisTemplate,
                DEFAULT_REFRESH_ROTATION_GRACE,
                Clock.systemUTC());
    }

    public JwtTokenService(
            String rawSecret,
            long accessTokenTtlSeconds,
            long refreshTokenTtlSeconds,
            long accessCookieMaxAgeSeconds,
            long refreshCookieMaxAgeSeconds,
            boolean cookieSecure,
            boolean allowGeneratedSecret,
            StringRedisTemplate redisTemplate) {
        this(
                rawSecret,
                accessTokenTtlSeconds,
                refreshTokenTtlSeconds,
                accessCookieMaxAgeSeconds,
                refreshCookieMaxAgeSeconds,
                cookieSecure,
                allowGeneratedSecret,
                false,
                redisTemplate,
                DEFAULT_REFRESH_ROTATION_GRACE,
                Clock.systemUTC());
    }

    public JwtTokenService(
            String rawSecret,
            long accessTokenTtlSeconds,
            long refreshTokenTtlSeconds,
            long accessCookieMaxAgeSeconds,
            long refreshCookieMaxAgeSeconds,
            boolean cookieSecure,
            boolean allowGeneratedSecret,
            boolean requireRedisTokenStore,
            StringRedisTemplate redisTemplate) {
        this(
                rawSecret,
                accessTokenTtlSeconds,
                refreshTokenTtlSeconds,
                accessCookieMaxAgeSeconds,
                refreshCookieMaxAgeSeconds,
                cookieSecure,
                allowGeneratedSecret,
                requireRedisTokenStore,
                redisTemplate,
                DEFAULT_REFRESH_ROTATION_GRACE,
                Clock.systemUTC());
    }

    public JwtTokenService(
            String rawSecret,
            long accessTokenTtlSeconds,
            long refreshTokenTtlSeconds,
            long accessCookieMaxAgeSeconds,
            long refreshCookieMaxAgeSeconds,
            boolean cookieSecure,
            boolean allowGeneratedSecret,
            boolean requireRedisTokenStore,
            StringRedisTemplate redisTemplate,
            MeterRegistry meterRegistry) {
        this(
                rawSecret,
                accessTokenTtlSeconds,
                refreshTokenTtlSeconds,
                accessCookieMaxAgeSeconds,
                refreshCookieMaxAgeSeconds,
                cookieSecure,
                allowGeneratedSecret,
                requireRedisTokenStore,
                redisTemplate,
                DEFAULT_REFRESH_ROTATION_GRACE,
                Clock.systemUTC(),
                meterRegistry);
    }

    JwtTokenService(
            String rawSecret,
            long accessTokenTtlSeconds,
            long refreshTokenTtlSeconds,
            long accessCookieMaxAgeSeconds,
            long refreshCookieMaxAgeSeconds,
            boolean cookieSecure,
            boolean allowGeneratedSecret,
            boolean requireRedisTokenStore,
            StringRedisTemplate redisTemplate,
            Duration refreshRotationGrace,
            Clock clock) {
        this(
                rawSecret,
                accessTokenTtlSeconds,
                refreshTokenTtlSeconds,
                accessCookieMaxAgeSeconds,
                refreshCookieMaxAgeSeconds,
                cookieSecure,
                allowGeneratedSecret,
                requireRedisTokenStore,
                redisTemplate,
                refreshRotationGrace,
                clock,
                Metrics.globalRegistry);
    }

    JwtTokenService(
            String rawSecret,
            long accessTokenTtlSeconds,
            long refreshTokenTtlSeconds,
            long accessCookieMaxAgeSeconds,
            long refreshCookieMaxAgeSeconds,
            boolean cookieSecure,
            boolean allowGeneratedSecret,
            boolean requireRedisTokenStore,
            StringRedisTemplate redisTemplate,
            Duration refreshRotationGrace,
            Clock clock,
            MeterRegistry meterRegistry) {
        this.signingSecret = resolveSigningSecret(rawSecret, allowGeneratedSecret);
        this.recoveryEncryptionKey = deriveRecoveryEncryptionKey(signingSecret);
        this.accessTokenTtlSeconds = Math.max(60L, accessTokenTtlSeconds);
        this.refreshTokenTtlSeconds = Math.max(3600L, refreshTokenTtlSeconds);
        this.accessCookieMaxAgeSeconds = accessCookieMaxAgeSeconds;
        this.refreshCookieMaxAgeSeconds = refreshCookieMaxAgeSeconds;
        this.cookieSecure = cookieSecure;
        this.redisTemplate = redisTemplate;
        this.requireRedisTokenStore = requireRedisTokenStore;
        this.refreshRotationGrace = normalizeRefreshRotationGrace(refreshRotationGrace);
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.meterRegistry = meterRegistry == null ? Metrics.globalRegistry : meterRegistry;
        if (requireRedisTokenStore && redisTemplate == null) {
            throw tokenStoreUnavailable("revocation and refresh-token state");
        }
    }

    public JwtTokenPair issueTokenPair(Long userId, String role) {
        return issueTokenPair(userId, role, List.of());
    }

    public JwtTokenPair issueTokenPair(Long userId, String role, Collection<String> authorities) {
        return issueTokenPair(userId, role, authorities, DEFAULT_TENANT_ID);
    }

    public JwtTokenPair issueTokenPair(Long userId, String role, Collection<String> authorities, Long tenantId) {
        Instant issuedAt = now();
        JwtTokenPair pair = createTokenPair(userId, role, authorities, tenantId, issuedAt);
        storeRefreshToken(pair.refreshTokenId(), issuedAt.plusSeconds(refreshTokenTtlSeconds));
        return pair;
    }

    private JwtTokenPair createTokenPair(
            Long userId, String role, Collection<String> authorities, Long tenantId, Instant issuedAt) {
        String accessJti = UUID.randomUUID().toString().replace("-", "");
        String refreshJti = UUID.randomUUID().toString().replace("-", "");
        List<String> normalizedAuthorities = normalizeAuthorities(role, authorities);
        Long normalizedTenantId = normalizeTenantId(tenantId);

        String accessToken = buildToken(
                userId,
                role,
                normalizedAuthorities,
                normalizedTenantId,
                TOKEN_TYPE_ACCESS,
                accessTokenTtlSeconds,
                accessJti,
                issuedAt);
        String refreshToken = buildToken(
                userId,
                role,
                normalizedAuthorities,
                normalizedTenantId,
                TOKEN_TYPE_REFRESH,
                refreshTokenTtlSeconds,
                refreshJti,
                issuedAt);
        return new JwtTokenPair(
                accessToken, refreshToken, accessJti, refreshJti, accessTokenTtlSeconds, refreshTokenTtlSeconds);
    }

    public void applyTokenCookies(HttpServletResponse response, SessionTokenPair pair) {
        addSecureCookie(response, COOKIE_ACCESS_TOKEN, pair.accessToken(), accessCookieMaxAgeSeconds);
        addSecureCookie(response, COOKIE_REFRESH_TOKEN, pair.refreshToken(), refreshCookieMaxAgeSeconds);
    }

    public void clearTokenCookies(HttpServletResponse response) {
        clearCookie(response, COOKIE_ACCESS_TOKEN);
        clearCookie(response, COOKIE_REFRESH_TOKEN);
    }

    public Optional<AuthenticatedAccessToken> parseAccessToken(String rawToken) {
        return parseToken(rawToken)
                .filter(token -> TOKEN_TYPE_ACCESS.equals(token.tokenType()))
                .flatMap(token -> {
                    if (isAccessTokenRevoked(token.tokenId())) {
                        recordJwtParseFailure(JWT_PARSE_REASON_REVOKED, true, null);
                        return Optional.empty();
                    }
                    return Optional.of(new AuthenticatedAccessToken(
                            token.userId(),
                            token.role(),
                            token.authorities(),
                            token.tokenId(),
                            token.expiration(),
                            token.tenantId()));
                });
    }

    public Optional<AuthenticatedRefreshToken> parseRefreshToken(String rawToken) {
        return parseToken(rawToken)
                .filter(token -> TOKEN_TYPE_REFRESH.equals(token.tokenType()))
                .flatMap(token -> {
                    if (!isRefreshTokenValid(token.tokenId())) {
                        recordJwtParseFailure(JWT_PARSE_REASON_REVOKED, true, null);
                        return Optional.empty();
                    }
                    return Optional.of(new AuthenticatedRefreshToken(
                            token.userId(),
                            token.role(),
                            token.authorities(),
                            token.tokenId(),
                            token.expiration(),
                            token.tenantId(),
                            token.issuedAt()));
                });
    }

    public Optional<JwtTokenPair> rotateRefreshToken(String refreshToken) {
        Optional<AuthenticatedRefreshToken> parsed = parseRefreshToken(refreshToken);
        if (parsed.isPresent()) {
            AuthenticatedRefreshToken token = parsed.orElseThrow();
            return Optional.of(rotateRefreshToken(token, token.role(), token.authorities()));
        }
        return recoverRefreshTokenRotation(refreshToken).map(RecoveredRefreshToken::tokenPair);
    }

    public JwtTokenPair rotateRefreshToken(
            AuthenticatedRefreshToken refreshToken, String currentRole, Collection<String> currentAuthorities) {
        if (refreshToken == null) {
            throw new IllegalArgumentException("refresh token is required");
        }
        if (requireRedisTokenStore) {
            return rotateRefreshTokenAtomically(refreshToken, currentRole, currentAuthorities);
        }
        synchronized (refreshRotationMonitor) {
            Optional<RefreshTokenRotation> completed = findRecoverableRefreshTokenRotation(refreshToken.tokenId());
            if (completed.isPresent()) {
                if (isRefreshTokenGenerationRevoked(refreshToken)) {
                    throw rejectRefreshTokenReuse(refreshToken);
                }
                return completed.orElseThrow().tokenPair();
            }
            return rotateRefreshTokenLocally(refreshToken, currentRole, currentAuthorities);
        }
    }

    @Override
    public Optional<RecoveredRefreshToken> recoverRefreshTokenRotation(String rawToken) {
        return parseTokenForRevocation(rawToken)
                .filter(token -> TOKEN_TYPE_REFRESH.equals(token.tokenType()))
                .flatMap(token -> findRecoverableRefreshTokenRotation(token.tokenId())
                        .filter(rotation -> token.userId().equals(rotation.userId()))
                        .filter(rotation -> token.tenantId().equals(rotation.tenantId()))
                        .filter(rotation -> parseRefreshToken(
                                        rotation.tokenPair().refreshToken())
                                .isPresent())
                        .map(rotation -> new RecoveredRefreshToken(
                                rotation.userId(), rotation.role(), rotation.tenantId(), rotation.tokenPair())));
    }

    public void revokeRefreshToken(AuthenticatedRefreshToken refreshToken) {
        if (refreshToken != null) {
            revokeRefreshTokenById(refreshToken.tokenId());
        }
    }

    public boolean revokeUserTokensForRefreshTokenReuse(String rawRefreshToken) {
        if (recoverRefreshTokenRotation(rawRefreshToken).isPresent()) {
            return false;
        }
        Optional<AuthenticatedToken> refreshToken =
                parseTokenForRevocation(rawRefreshToken).filter(token -> TOKEN_TYPE_REFRESH.equals(token.tokenType()));
        refreshToken.ifPresent(token -> revokeUserTokens(token.userId()));
        return refreshToken.isPresent();
    }

    public Optional<String> resolveAccessToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (StringUtils.hasText(header) && header.startsWith(SECURITY_PREFIX)) {
            return Optional.of(header.substring(SECURITY_PREFIX.length()));
        }
        return resolveCookie(request, COOKIE_ACCESS_TOKEN);
    }

    public String resolveRefreshToken(HttpServletRequest request) {
        return resolveCookie(request, COOKIE_REFRESH_TOKEN).orElse(null);
    }

    public void revokeTokens(HttpServletRequest request, HttpServletResponse response) {
        Optional<String> rawAccess = resolveAccessToken(request);
        Optional<String> rawRefresh = Optional.ofNullable(resolveRefreshToken(request));

        rawAccess
                .flatMap(this::parseTokenForRevocation)
                .filter(token -> TOKEN_TYPE_ACCESS.equals(token.tokenType()))
                .ifPresent(token -> revokeAccessTokenById(token.tokenId(), token.expiration()));
        rawRefresh
                .flatMap(this::parseTokenForRevocation)
                .filter(token -> TOKEN_TYPE_REFRESH.equals(token.tokenType()))
                .ifPresent(token -> revokeRefreshTokenById(token.tokenId()));
        clearTokenCookies(response);
    }

    public void revokeUserTokens(Long userId, HttpServletResponse response) {
        revokeUserTokens(userId);
        clearTokenCookies(response);
    }

    public void revokeUserTokens(Long userId) {
        revokeUserTokensIssuedBefore(userId, now());
    }

    public void revokeAccessToken(String rawAccessToken) {
        parseTokenForRevocation(rawAccessToken)
                .filter(token -> TOKEN_TYPE_ACCESS.equals(token.tokenType()))
                .ifPresent(token -> revokeAccessTokenById(token.tokenId(), token.expiration()));
    }

    private Optional<AuthenticatedToken> parseToken(String rawToken) {
        return parseToken(rawToken, true);
    }

    private Optional<AuthenticatedToken> parseTokenForRevocation(String rawToken) {
        return parseToken(rawToken, false);
    }

    private Optional<AuthenticatedToken> parseToken(String rawToken, boolean enforceRevocationState) {
        if (!StringUtils.hasText(rawToken)) {
            return Optional.empty();
        }
        try {
            SignedJWT signedJwt = SignedJWT.parse(rawToken);
            if (!JWSAlgorithm.HS256.equals(signedJwt.getHeader().getAlgorithm())
                    || !signedJwt.verify(new MACVerifier(signingSecret))) {
                recordJwtParseFailure(JWT_PARSE_REASON_INVALID_SIGNATURE, enforceRevocationState, null);
                return Optional.empty();
            }
            JWTClaimsSet claims = signedJwt.getJWTClaimsSet();
            String tokenId = claims.getJWTID();
            Long userId = Long.valueOf(claims.getSubject());
            String role = claims.getStringClaim(CLAIM_ROLE);
            List<String> authorities = normalizeAuthorities(role, claims.getStringListClaim(CLAIM_AUTHORITIES));
            Long tenantId = normalizeTenantId(claims.getLongClaim(CLAIM_TENANT_ID));
            String tokenType = claims.getStringClaim(CLAIM_TOKEN_TYPE);
            Date issuedAtDate = claims.getIssueTime();
            Date expirationDate = claims.getExpirationTime();
            Date notBeforeDate = claims.getNotBeforeTime();
            Instant expiresAt = expirationDate == null ? null : expirationDate.toInstant();
            Instant issuedAt = issuedAtDate == null ? null : issuedAtDate.toInstant();

            if (expirationDate == null
                    || notBeforeDate == null
                    || !StringUtils.hasText(tokenId)
                    || userId <= 0
                    || !StringUtils.hasText(role)
                    || !StringUtils.hasText(tokenType)
                    || issuedAt == null
                    || !JWT_ISSUER.equals(claims.getIssuer())
                    || claims.getAudience() == null
                    || !claims.getAudience().contains(JWT_AUDIENCE)) {
                recordJwtParseFailure(JWT_PARSE_REASON_MALFORMED, enforceRevocationState, null);
                return Optional.empty();
            }
            Instant currentTime = now();
            if (!expiresAt.isAfter(currentTime)) {
                recordJwtParseFailure(JWT_PARSE_REASON_EXPIRED, enforceRevocationState, null);
                return Optional.empty();
            }
            if (notBeforeDate.toInstant().isAfter(currentTime)) {
                recordJwtParseFailure(JWT_PARSE_REASON_MALFORMED, enforceRevocationState, null);
                return Optional.empty();
            }
            if (enforceRevocationState && isUserTokenRevoked(userId, issuedAt)) {
                recordJwtParseFailure(JWT_PARSE_REASON_REVOKED, true, null);
                return Optional.empty();
            }
            return Optional.of(new AuthenticatedToken(
                    userId, role, authorities, tenantId, tokenType, tokenId, expiresAt, issuedAt));
        } catch (ParseException | NumberFormatException exception) {
            recordJwtParseFailure(JWT_PARSE_REASON_MALFORMED, enforceRevocationState, exception);
            return Optional.empty();
        } catch (JOSEException | RuntimeException exception) {
            recordJwtParseFailure(JWT_PARSE_REASON_UNEXPECTED, enforceRevocationState, exception);
            return Optional.empty();
        }
    }

    private void recordJwtParseFailure(String reason, boolean requestToken, Throwable exception) {
        try {
            meterRegistry.counter(JWT_PARSE_FAILURE_METRIC, "reason", reason).increment();
        } catch (RuntimeException metricFailure) {
            log.debug(
                    "JWT parse failure metric unavailable; exception={}",
                    metricFailure.getClass().getSimpleName());
        }

        String tokenContext = requestToken ? "request" : "revocation";
        if (JWT_PARSE_REASON_UNEXPECTED.equals(reason)) {
            log.warn(
                    "Unexpected JWT parsing failure; context={}; reason={}; exception={}",
                    tokenContext,
                    reason,
                    exception == null ? "unknown" : exception.getClass().getSimpleName());
            return;
        }
        if (exception == null) {
            log.debug("JWT token rejected while parsing {} token; reason={}", tokenContext, reason);
            return;
        }
        log.debug(
                "JWT token rejected while parsing {} token; reason={}; exception={}",
                tokenContext,
                reason,
                exception.getClass().getSimpleName());
    }

    private Optional<String> resolveCookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) {
            return Optional.empty();
        }
        for (Cookie cookie : request.getCookies()) {
            if (name.equals(cookie.getName())) {
                return Optional.ofNullable(cookie.getValue());
            }
        }
        return Optional.empty();
    }

    private void addSecureCookie(HttpServletResponse response, String name, String value, long maxAgeSeconds) {
        ResponseCookie cookie = ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAgeSeconds)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void clearCookie(HttpServletResponse response, String name) {
        ResponseCookie cookie = ResponseCookie.from(name, "")
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Lax")
                .path("/")
                .maxAge(0)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private String buildToken(
            Long userId,
            String role,
            List<String> authorities,
            Long tenantId,
            String tokenType,
            long ttlSeconds,
            String jti,
            Instant now) {
        Instant expiry = now.plusSeconds(ttlSeconds);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(JWT_ISSUER)
                .audience(JWT_AUDIENCE)
                .subject(userId.toString())
                .claim(CLAIM_ROLE, role)
                .claim(CLAIM_AUTHORITIES, authorities)
                .claim(CLAIM_TENANT_ID, normalizeTenantId(tenantId))
                .claim(CLAIM_TOKEN_TYPE, tokenType)
                .jwtID(jti)
                .issueTime(Date.from(now))
                .notBeforeTime(Date.from(now))
                .expirationTime(Date.from(expiry))
                .build();
        SignedJWT signedJwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.HS256)
                        .type(JOSEObjectType.JWT)
                        .build(),
                claims);
        try {
            signedJwt.sign(new MACSigner(signingSecret));
        } catch (JOSEException e) {
            throw new IllegalStateException("JWT signing failed", e);
        }
        return signedJwt.serialize();
    }

    private JwtTokenPair rotateRefreshTokenLocally(
            AuthenticatedRefreshToken refreshToken, String currentRole, Collection<String> currentAuthorities) {
        if (isRefreshTokenGenerationRevoked(refreshToken) || !isRefreshTokenValid(refreshToken.tokenId())) {
            throw rejectRefreshTokenReuse(refreshToken);
        }
        revokeRefreshTokenById(refreshToken.tokenId());
        JwtTokenPair pair =
                issueTokenPair(refreshToken.userId(), currentRole, currentAuthorities, refreshToken.tenantId());
        storeRefreshTokenRotation(refreshToken, currentRole, pair);
        return pair;
    }

    private JwtTokenPair rotateRefreshTokenAtomically(
            AuthenticatedRefreshToken refreshToken, String currentRole, Collection<String> currentAuthorities) {
        if (refreshToken.issuedAt() == null) {
            throw rejectRefreshTokenReuse(refreshToken);
        }
        Instant issuedAt = now();
        JwtTokenPair pair = createTokenPair(
                refreshToken.userId(), currentRole, currentAuthorities, refreshToken.tenantId(), issuedAt);
        RefreshTokenRotation rotation = new RefreshTokenRotation(
                refreshToken.userId(), currentRole, refreshToken.tenantId(), pair, issuedAt.plus(refreshRotationGrace));
        String encryptedRotation = encryptRotation(refreshToken.tokenId(), rotation);
        List<String> keys = List.of(
                REDIS_REFRESH_TOKEN_PREFIX + refreshToken.tokenId(),
                REDIS_REFRESH_TOKEN_PREFIX + pair.refreshTokenId(),
                REDIS_REFRESH_ROTATION_PREFIX + refreshToken.tokenId(),
                REDIS_REVOKED_USER_PREFIX + refreshToken.userId());
        long refreshTtlMillis = Duration.ofSeconds(refreshTokenTtlSeconds).toMillis();
        long recoveryTtlMillis = Math.max(1L, refreshRotationGrace.toMillis());
        String result;
        try {
            result = redisTemplate.execute(
                    ROTATE_REFRESH_TOKEN,
                    keys,
                    Long.toString(refreshToken.issuedAt().toEpochMilli()),
                    Long.toString(refreshTtlMillis),
                    Long.toString(recoveryTtlMillis),
                    encryptedRotation);
        } catch (Exception e) {
            throw tokenStoreUnavailable("refresh-token rotation", e);
        }
        if (ROTATION_STATUS_ROTATED.equals(result)) {
            return pair;
        }
        if (ROTATION_STATUS_REUSED.equals(result) || ROTATION_STATUS_REVOKED.equals(result)) {
            throw rejectRefreshTokenReuse(refreshToken);
        }
        if (!StringUtils.hasText(result)) {
            throw tokenStoreUnavailable("refresh-token rotation");
        }
        return recoverAtomicRefreshTokenRotation(refreshToken, result);
    }

    private RefreshTokenReuseException rejectRefreshTokenReuse(AuthenticatedRefreshToken refreshToken) {
        revokeUserTokens(refreshToken.userId());
        return new RefreshTokenReuseException(refreshToken.userId(), refreshToken.role());
    }

    private JwtTokenPair recoverAtomicRefreshTokenRotation(
            AuthenticatedRefreshToken refreshToken, String encryptedRotation) {
        RefreshTokenRotation rotation = decryptRotation(refreshToken.tokenId(), encryptedRotation)
                .filter(candidate -> refreshToken.userId().equals(candidate.userId()))
                .filter(candidate -> refreshToken.tenantId().equals(candidate.tenantId()))
                .filter(candidate -> now().isBefore(candidate.recoverUntil()))
                .orElseThrow(() -> tokenStoreUnavailable("refresh-token rotation recovery"));
        if (parseRefreshToken(rotation.tokenPair().refreshToken()).isEmpty()) {
            throw rejectRefreshTokenReuse(refreshToken);
        }
        return rotation.tokenPair();
    }

    private boolean isRefreshTokenGenerationRevoked(AuthenticatedRefreshToken refreshToken) {
        return refreshToken.issuedAt() == null || isUserTokenRevoked(refreshToken.userId(), refreshToken.issuedAt());
    }

    private void storeRefreshTokenRotation(
            AuthenticatedRefreshToken refreshToken, String currentRole, JwtTokenPair pair) {
        RefreshTokenRotation rotation = new RefreshTokenRotation(
                refreshToken.userId(), currentRole, refreshToken.tenantId(), pair, now().plus(refreshRotationGrace));
        if (!requireRedisTokenStore) {
            refreshTokenRotations.put(refreshToken.tokenId(), rotation);
        }
        requireRedisSuccess(
                storeRefreshTokenRotationInRedis(refreshToken.tokenId(), rotation), "refresh-token rotation recovery");
    }

    private Optional<RefreshTokenRotation> findRecoverableRefreshTokenRotation(String refreshTokenId) {
        if (!StringUtils.hasText(refreshTokenId)) {
            return Optional.empty();
        }
        Instant current = now();
        purgeExpiredEntries(current);
        if (!requireRedisTokenStore) {
            RefreshTokenRotation local = refreshTokenRotations.get(refreshTokenId);
            if (local != null && current.isBefore(local.recoverUntil())) {
                return Optional.of(local);
            }
        }
        return readRefreshTokenRotationFromRedis(refreshTokenId)
                .filter(rotation -> current.isBefore(rotation.recoverUntil()));
    }

    private boolean storeRefreshTokenRotationInRedis(String refreshTokenId, RefreshTokenRotation rotation) {
        if (redisTemplate == null || !StringUtils.hasText(refreshTokenId) || rotation == null) {
            return false;
        }
        Duration ttl = Duration.between(now(), rotation.recoverUntil());
        if (ttl.isZero() || ttl.isNegative()) {
            return false;
        }
        try {
            redisTemplate
                    .opsForValue()
                    .set(
                            REDIS_REFRESH_ROTATION_PREFIX + refreshTokenId,
                            encryptRotation(refreshTokenId, rotation),
                            ttl);
            return true;
        } catch (Exception e) {
            log.debug("Redis write for refresh-token rotation failed", e);
            return false;
        }
    }

    private Optional<RefreshTokenRotation> readRefreshTokenRotationFromRedis(String refreshTokenId) {
        if (redisTemplate == null || !StringUtils.hasText(refreshTokenId)) {
            return Optional.empty();
        }
        try {
            return decryptRotation(
                    refreshTokenId, redisTemplate.opsForValue().get(REDIS_REFRESH_ROTATION_PREFIX + refreshTokenId));
        } catch (Exception e) {
            if (requireRedisTokenStore) {
                throw tokenStoreUnavailable("refresh-token rotation recovery", e);
            }
            log.debug("Redis read for refresh-token rotation failed, fallback to in-memory", e);
            return Optional.empty();
        }
    }

    private String encryptRotation(String refreshTokenId, RefreshTokenRotation rotation) {
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            SECURE_RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(recoveryEncryptionKey, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(rotationAdditionalAuthenticatedData(refreshTokenId));
            byte[] ciphertext = cipher.doFinal(serializeRotation(rotation));
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            return String.join(
                    ".", ROTATION_CIPHERTEXT_VERSION, encoder.encodeToString(iv), encoder.encodeToString(ciphertext));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("JWT refresh-token rotation recovery encryption failed", e);
        }
    }

    private Optional<RefreshTokenRotation> decryptRotation(String refreshTokenId, String value) {
        if (!StringUtils.hasText(refreshTokenId) || !StringUtils.hasText(value)) {
            return Optional.empty();
        }
        String[] fields = value.split("\\.", -1);
        if (fields.length != 3 || !ROTATION_CIPHERTEXT_VERSION.equals(fields[0])) {
            return Optional.empty();
        }
        try {
            Base64.Decoder decoder = Base64.getUrlDecoder();
            byte[] iv = decoder.decode(fields[1]);
            if (iv.length != GCM_IV_BYTES) {
                return Optional.empty();
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(recoveryEncryptionKey, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(rotationAdditionalAuthenticatedData(refreshTokenId));
            return deserializeRotation(cipher.doFinal(decoder.decode(fields[2])));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            log.warn(
                    "JWT refresh-token rotation recovery ciphertext rejected; reason={}",
                    e.getClass().getSimpleName());
            log.debug("JWT refresh-token rotation recovery ciphertext rejection details", e);
            return Optional.empty();
        }
    }

    private static byte[] serializeRotation(RefreshTokenRotation rotation) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                DataOutputStream output = new DataOutputStream(bytes)) {
            JwtTokenPair pair = rotation.tokenPair();
            output.writeLong(rotation.userId());
            output.writeUTF(rotation.role());
            output.writeLong(rotation.tenantId());
            output.writeUTF(pair.accessToken());
            output.writeUTF(pair.refreshToken());
            output.writeUTF(pair.accessTokenId());
            output.writeUTF(pair.refreshTokenId());
            output.writeLong(pair.accessTtlSeconds());
            output.writeLong(pair.refreshTtlSeconds());
            output.writeLong(rotation.recoverUntil().toEpochMilli());
            output.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("JWT refresh-token rotation recovery serialization failed", e);
        }
    }

    private static Optional<RefreshTokenRotation> deserializeRotation(byte[] value) {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(value))) {
            Long userId = input.readLong();
            String role = input.readUTF();
            Long tenantId = input.readLong();
            JwtTokenPair pair = new JwtTokenPair(
                    input.readUTF(),
                    input.readUTF(),
                    input.readUTF(),
                    input.readUTF(),
                    input.readLong(),
                    input.readLong());
            Instant recoverUntil = Instant.ofEpochMilli(input.readLong());
            if (input.available() != 0) {
                return Optional.empty();
            }
            return Optional.of(new RefreshTokenRotation(userId, role, tenantId, pair, recoverUntil));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static byte[] rotationAdditionalAuthenticatedData(String refreshTokenId) {
        return (REDIS_REFRESH_ROTATION_PREFIX + refreshTokenId).getBytes(StandardCharsets.UTF_8);
    }

    private boolean isRefreshTokenValid(String tokenId) {
        if (!StringUtils.hasText(tokenId)) {
            return false;
        }
        Instant now = now();
        purgeExpiredEntries(now);
        if (requireRedisTokenStore) {
            return isRefreshTokenStoredInRedis(tokenId);
        }
        Instant expiresAt = refreshTokens.get(tokenId);
        if (expiresAt != null && now.isBefore(expiresAt)) {
            return true;
        }
        return isRefreshTokenStoredInRedis(tokenId);
    }

    private boolean isRefreshTokenStoredInRedis(String tokenId) {
        if (redisTemplate == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(REDIS_REFRESH_TOKEN_PREFIX + tokenId));
        } catch (Exception e) {
            log.debug("Redis read for refresh token failed, fallback to in-memory lookup", e);
            return false;
        }
    }

    private void storeRefreshToken(String refreshTokenId, Instant expiresAt) {
        if (!StringUtils.hasText(refreshTokenId) || expiresAt == null) {
            return;
        }
        purgeExpiredEntries(now());
        if (!requireRedisTokenStore) {
            refreshTokens.put(refreshTokenId, expiresAt);
        }
        requireRedisSuccess(storeRefreshTokenInRedis(refreshTokenId, expiresAt), "refresh-token storage");
    }

    private void revokeRefreshTokenById(String refreshTokenId) {
        if (!StringUtils.hasText(refreshTokenId)) {
            return;
        }
        if (!requireRedisTokenStore) {
            refreshTokens.remove(refreshTokenId);
        }
        requireRedisSuccess(removeRefreshTokenFromRedis(refreshTokenId), "refresh-token revocation");
    }

    private void revokeAccessTokenById(String tokenId, Instant expiresAt) {
        if (!StringUtils.hasText(tokenId)) {
            return;
        }
        if (!requireRedisTokenStore) {
            revokedAccessTokens.put(tokenId, expiresAt == null ? Instant.EPOCH : expiresAt);
        }
        requireRedisSuccess(storeRevokedAccessTokenInRedis(tokenId, expiresAt), "access-token revocation");
    }

    void revokeUserTokensIssuedBefore(Long userId, Instant issuedBefore) {
        if (userId == null || userId <= 0 || issuedBefore == null) {
            return;
        }
        if (!requireRedisTokenStore) {
            revokedUserTokensIssuedBefore.put(userId, issuedBefore);
        }
        requireRedisSuccess(storeUserTokenRevocationInRedis(userId, issuedBefore), "user-token revocation");
    }

    private boolean isAccessTokenRevoked(String tokenId) {
        purgeExpiredEntries(now());
        if (requireRedisTokenStore) {
            return isAccessTokenRevokedInRedis(tokenId);
        }
        if (revokedAccessTokens.containsKey(tokenId)) {
            return true;
        }
        return isAccessTokenRevokedInRedis(tokenId);
    }

    private boolean isUserTokenRevoked(Long userId, Instant issuedAt) {
        if (userId == null || issuedAt == null) {
            return true;
        }
        purgeExpiredEntries(now());
        if (requireRedisTokenStore) {
            return isUserTokenRevokedInRedis(userId, issuedAt);
        }
        Instant memoryRevokedBefore = revokedUserTokensIssuedBefore.get(userId);
        if (memoryRevokedBefore != null && !issuedAt.isAfter(memoryRevokedBefore)) {
            return true;
        }
        return readUserTokenRevocationFromRedis(userId)
                .filter(revokedBefore -> !issuedAt.isAfter(revokedBefore))
                .isPresent();
    }

    private boolean isAccessTokenRevokedInRedis(String tokenId) {
        if (redisTemplate == null || !StringUtils.hasText(tokenId)) {
            return requireRedisTokenStore;
        }
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(REDIS_REVOKED_ACCESS_PREFIX + tokenId));
        } catch (Exception e) {
            log.debug("Redis read for revoked access token failed", e);
            return requireRedisTokenStore || revokedAccessTokens.containsKey(tokenId);
        }
    }

    private boolean isUserTokenRevokedInRedis(Long userId, Instant issuedAt) {
        if (redisTemplate == null || userId == null || issuedAt == null) {
            return true;
        }
        try {
            String value = redisTemplate.opsForValue().get(REDIS_REVOKED_USER_PREFIX + userId);
            if (!StringUtils.hasText(value)) {
                return false;
            }
            Instant revokedBefore = Instant.ofEpochMilli(Long.parseLong(value));
            return !issuedAt.isAfter(revokedBefore);
        } catch (Exception e) {
            log.debug("Redis read for user token revocation failed", e);
            return true;
        }
    }

    private boolean storeRevokedAccessTokenInRedis(String tokenId, Instant expiresAt) {
        if (redisTemplate == null || !StringUtils.hasText(tokenId) || expiresAt == null) {
            return false;
        }
        Duration ttl = Duration.between(now(), expiresAt);
        if (ttl.isZero() || ttl.isNegative()) {
            return true;
        }
        try {
            redisTemplate.opsForValue().setIfAbsent(REDIS_REVOKED_ACCESS_PREFIX + tokenId, "1", ttl);
            return true;
        } catch (Exception e) {
            log.debug("Redis write for revoked access token failed", e);
            return false;
        }
    }

    private boolean storeUserTokenRevocationInRedis(Long userId, Instant issuedBefore) {
        if (redisTemplate == null || userId == null || issuedBefore == null) {
            return false;
        }
        try {
            redisTemplate
                    .opsForValue()
                    .set(
                            REDIS_REVOKED_USER_PREFIX + userId,
                            Long.toString(issuedBefore.toEpochMilli()),
                            Duration.ofSeconds(refreshTokenTtlSeconds));
            return true;
        } catch (Exception e) {
            log.debug("Redis write for user token revocation failed", e);
            return false;
        }
    }

    private Optional<Instant> readUserTokenRevocationFromRedis(Long userId) {
        if (redisTemplate == null || userId == null) {
            return Optional.empty();
        }
        try {
            String value = redisTemplate.opsForValue().get(REDIS_REVOKED_USER_PREFIX + userId);
            if (!StringUtils.hasText(value)) {
                return Optional.empty();
            }
            Instant revokedBefore = Instant.ofEpochMilli(Long.parseLong(value));
            revokedUserTokensIssuedBefore.put(userId, revokedBefore);
            return Optional.of(revokedBefore);
        } catch (Exception e) {
            log.debug("Redis read for user token revocation failed, fallback to in-memory", e);
            return Optional.ofNullable(revokedUserTokensIssuedBefore.get(userId));
        }
    }

    private boolean storeRefreshTokenInRedis(String refreshTokenId, Instant expiresAt) {
        if (redisTemplate == null || !StringUtils.hasText(refreshTokenId) || expiresAt == null) {
            return false;
        }
        Duration ttl = Duration.between(now(), expiresAt);
        if (ttl.isZero() || ttl.isNegative()) {
            return true;
        }
        try {
            redisTemplate.opsForValue().set(REDIS_REFRESH_TOKEN_PREFIX + refreshTokenId, "1", ttl);
            return true;
        } catch (Exception e) {
            log.debug("Redis write for refresh token failed", e);
            return false;
        }
    }

    private boolean removeRefreshTokenFromRedis(String refreshTokenId) {
        if (redisTemplate == null || !StringUtils.hasText(refreshTokenId)) {
            return false;
        }
        try {
            redisTemplate.delete(REDIS_REFRESH_TOKEN_PREFIX + refreshTokenId);
            return true;
        } catch (Exception e) {
            log.debug("Redis delete for refresh token failed", e);
            return false;
        }
    }

    private void requireRedisSuccess(boolean success, String action) {
        if (requireRedisTokenStore && !success) {
            throw tokenStoreUnavailable(action);
        }
    }

    private static IllegalStateException tokenStoreUnavailable(String action) {
        return new IllegalStateException("Redis token store is required for JWT " + action);
    }

    private static IllegalStateException tokenStoreUnavailable(String action, Exception cause) {
        return new IllegalStateException("Redis token store is required for JWT " + action, cause);
    }

    private void purgeExpiredEntries(Instant now) {
        refreshTokens.entrySet().removeIf(entry -> now.isAfter(entry.getValue()));
        refreshTokenRotations
                .entrySet()
                .removeIf(entry -> !now.isBefore(entry.getValue().recoverUntil()));
        revokedAccessTokens.entrySet().removeIf(entry -> now.isAfter(entry.getValue()));
        revokedUserTokensIssuedBefore
                .entrySet()
                .removeIf(entry -> now.isAfter(entry.getValue().plusSeconds(refreshTokenTtlSeconds)));
    }

    private Instant now() {
        return clock.instant();
    }

    private static Duration normalizeRefreshRotationGrace(Duration grace) {
        if (grace == null || grace.isZero() || grace.isNegative()) {
            return DEFAULT_REFRESH_ROTATION_GRACE;
        }
        return grace.compareTo(Duration.ofSeconds(30)) > 0 ? Duration.ofSeconds(30) : grace;
    }

    private static byte[] deriveRecoveryEncryptionKey(byte[] signingSecret) {
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(ROTATION_KEY_DERIVATION_SALT.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] pseudoRandomKey = hmac.doFinal(signingSecret);
            hmac.init(new SecretKeySpec(pseudoRandomKey, "HmacSHA256"));
            hmac.update(ROTATION_KEY_DERIVATION_INFO.getBytes(StandardCharsets.UTF_8));
            hmac.update((byte) 1);
            return hmac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("JWT refresh-token rotation recovery key derivation failed", e);
        }
    }

    private static byte[] resolveSigningSecret(String rawSecret, boolean allowGeneratedSecret) {
        if (!StringUtils.hasText(rawSecret) && !allowGeneratedSecret) {
            throw new IllegalStateException("APP_JWT_SECRET must be set for JWT signing");
        }
        byte[] keyBytes =
                StringUtils.hasText(rawSecret) ? rawSecret.getBytes(StandardCharsets.UTF_8) : generateFallbackSecret();
        if (keyBytes.length < MIN_HMAC_SECRET_BYTES) {
            throw new IllegalStateException("APP_JWT_SECRET must be at least 32 bytes for HS256 signing");
        }
        return keyBytes.clone();
    }

    private static byte[] generateFallbackSecret() {
        byte[] keyBytes = new byte[64];
        SECURE_RANDOM.nextBytes(keyBytes);
        return Base64.getEncoder().encode(keyBytes);
    }

    private static List<String> normalizeAuthorities(String role, Collection<String> authorities) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        String normalizedRole = "ADMIN".equals(role) ? "ADMIN" : "USER";
        normalized.add("ROLE_" + normalizedRole);
        if (authorities != null) {
            authorities.stream().filter(StringUtils::hasText).map(String::trim).forEach(normalized::add);
        }
        return List.copyOf(normalized);
    }

    private static Long normalizeTenantId(Long tenantId) {
        return tenantId == null || tenantId <= 0 ? DEFAULT_TENANT_ID : tenantId;
    }

    private record RefreshTokenRotation(
            Long userId, String role, Long tenantId, JwtTokenPair tokenPair, Instant recoverUntil) {}

    private static final record AuthenticatedToken(
            Long userId,
            String role,
            List<String> authorities,
            Long tenantId,
            String tokenType,
            String tokenId,
            Instant expiration,
            Instant issuedAt) {}
}
