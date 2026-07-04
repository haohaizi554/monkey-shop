package com.example.monkey.user.infrastructure;

import com.example.monkey.shared.application.security.SessionTokenPair;
import com.example.monkey.shared.domain.security.JwtTokenPair;
import com.example.monkey.shared.interfaces.web.SessionTokenTransport;
import com.example.monkey.user.domain.SessionTokenService;
import com.example.monkey.user.domain.SessionTokenService.AuthenticatedAccessToken;
import com.example.monkey.user.domain.SessionTokenService.AuthenticatedRefreshToken;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.ParseException;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
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
    private static final String COOKIE_ACCESS_TOKEN = "access_token";
    private static final String COOKIE_REFRESH_TOKEN = "refresh_token";
    private static final String SECURITY_PREFIX = "Bearer ";
    private static final String REDIS_REFRESH_TOKEN_PREFIX = "jwt:refresh:";
    private static final String REDIS_REVOKED_ACCESS_PREFIX = "jwt:revoked:access:";
    private static final String REDIS_REVOKED_USER_PREFIX = "jwt:revoked:user:";
    private static final int MIN_HMAC_SECRET_BYTES = 32;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final byte[] signingSecret;
    private final long accessTokenTtlSeconds;
    private final long refreshTokenTtlSeconds;
    private final long accessCookieMaxAgeSeconds;
    private final long refreshCookieMaxAgeSeconds;
    private final boolean cookieSecure;
    private final StringRedisTemplate redisTemplate;
    private final boolean requireRedisTokenStore;
    private final Map<String, Instant> refreshTokens = new ConcurrentHashMap<>();
    private final Map<String, Instant> revokedAccessTokens = new ConcurrentHashMap<>();
    private final Map<Long, Instant> revokedUserTokensIssuedBefore = new ConcurrentHashMap<>();

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
                null);
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
                redisTemplate);
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
                redisTemplate);
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
        this.signingSecret = resolveSigningSecret(rawSecret, allowGeneratedSecret);
        this.accessTokenTtlSeconds = Math.max(60L, accessTokenTtlSeconds);
        this.refreshTokenTtlSeconds = Math.max(3600L, refreshTokenTtlSeconds);
        this.accessCookieMaxAgeSeconds = accessCookieMaxAgeSeconds;
        this.refreshCookieMaxAgeSeconds = refreshCookieMaxAgeSeconds;
        this.cookieSecure = cookieSecure;
        this.redisTemplate = redisTemplate;
        this.requireRedisTokenStore = requireRedisTokenStore;
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
        Instant now = Instant.now();
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
                now);
        String refreshToken = buildToken(
                userId,
                role,
                normalizedAuthorities,
                normalizedTenantId,
                TOKEN_TYPE_REFRESH,
                refreshTokenTtlSeconds,
                refreshJti,
                now);
        storeRefreshToken(refreshJti, Instant.now().plusSeconds(refreshTokenTtlSeconds));
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
                .filter(token -> !isAccessTokenRevoked(token.tokenId()))
                .map(token -> new AuthenticatedAccessToken(
                        token.userId(),
                        token.role(),
                        token.authorities(),
                        token.tokenId(),
                        token.expiration(),
                        token.tenantId()));
    }

    public Optional<AuthenticatedRefreshToken> parseRefreshToken(String rawToken) {
        return parseToken(rawToken)
                .filter(token -> TOKEN_TYPE_REFRESH.equals(token.tokenType()))
                .filter(token -> isRefreshTokenValid(token.tokenId()))
                .map(token -> new AuthenticatedRefreshToken(
                        token.userId(),
                        token.role(),
                        token.authorities(),
                        token.tokenId(),
                        token.expiration(),
                        token.tenantId()));
    }

    public Optional<JwtTokenPair> rotateRefreshToken(String refreshToken) {
        return parseRefreshToken(refreshToken).map(token -> {
            return rotateRefreshToken(token, token.role(), token.authorities());
        });
    }

    public JwtTokenPair rotateRefreshToken(
            AuthenticatedRefreshToken refreshToken, String currentRole, Collection<String> currentAuthorities) {
        revokeRefreshTokenById(refreshToken.tokenId());
        return issueTokenPair(refreshToken.userId(), currentRole, currentAuthorities, refreshToken.tenantId());
    }

    public void revokeRefreshToken(AuthenticatedRefreshToken refreshToken) {
        if (refreshToken != null) {
            revokeRefreshTokenById(refreshToken.tokenId());
        }
    }

    public boolean revokeUserTokensForRefreshTokenReuse(String rawRefreshToken) {
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
        revokeUserTokensIssuedBefore(userId, Instant.now());
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
            Instant expiresAt = expirationDate == null ? null : expirationDate.toInstant();
            Instant issuedAt = issuedAtDate == null ? null : issuedAtDate.toInstant();

            if (expirationDate == null || expirationDate.before(new Date())) {
                return Optional.empty();
            }
            if (!StringUtils.hasText(tokenId)
                    || userId <= 0
                    || !StringUtils.hasText(role)
                    || !StringUtils.hasText(tokenType)
                    || issuedAt == null
                    || (enforceRevocationState && isUserTokenRevoked(userId, issuedAt))) {
                return Optional.empty();
            }
            return Optional.of(
                    new AuthenticatedToken(userId, role, authorities, tenantId, tokenType, tokenId, expiresAt));
        } catch (ParseException | JOSEException | RuntimeException exception) {
            log.warn(
                    "JWT token rejected while parsing {} token; reason={}",
                    enforceRevocationState ? "request" : "revocation",
                    exception.getClass().getSimpleName());
            log.debug("JWT token rejection details", exception);
            return Optional.empty();
        }
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
                .subject(userId.toString())
                .claim(CLAIM_ROLE, role)
                .claim(CLAIM_AUTHORITIES, authorities)
                .claim(CLAIM_TENANT_ID, normalizeTenantId(tenantId))
                .claim(CLAIM_TOKEN_TYPE, tokenType)
                .jwtID(jti)
                .issueTime(Date.from(now))
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

    private boolean isRefreshTokenValid(String tokenId) {
        if (!StringUtils.hasText(tokenId)) {
            return false;
        }
        Instant now = Instant.now();
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
        purgeExpiredEntries(Instant.now());
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
        purgeExpiredEntries(Instant.now());
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
        purgeExpiredEntries(Instant.now());
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
        Duration ttl = Duration.between(Instant.now(), expiresAt);
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
        Duration ttl = Duration.between(Instant.now(), expiresAt);
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

    private void purgeExpiredEntries(Instant now) {
        refreshTokens.entrySet().removeIf(entry -> now.isAfter(entry.getValue()));
        revokedAccessTokens.entrySet().removeIf(entry -> now.isAfter(entry.getValue()));
        revokedUserTokensIssuedBefore
                .entrySet()
                .removeIf(entry -> now.isAfter(entry.getValue().plusSeconds(refreshTokenTtlSeconds)));
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

    private static final record AuthenticatedToken(
            Long userId,
            String role,
            List<String> authorities,
            Long tenantId,
            String tokenType,
            String tokenId,
            Instant expiration) {}
}
