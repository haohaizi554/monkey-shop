package com.example.monkey.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.domain.user.SessionTokenService.AuthenticatedAccessToken;
import com.example.monkey.domain.user.SessionTokenService.AuthenticatedRefreshToken;
import com.example.monkey.domain.user.SessionTokenService.JwtTokenPair;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class JwtTokenServiceTest {

    private static final String TEST_SECRET = "unit-test-secret-key-should-be-long-enough-for-hmac";

    @Test
    void missingJwtSecretIsRejectedByDefault() {
        assertThatThrownBy(() -> new JwtTokenService("", 900, 604800, 900, 604800, false, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_JWT_SECRET must be set");
    }

    @Test
    void shortJwtSecretIsRejected() {
        assertThatThrownBy(() -> new JwtTokenService("too-short", 900, 604800, 900, 604800, false, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_JWT_SECRET must be at least 32 bytes");
    }

    @Test
    void generatedJwtSecretRequiresExplicitOptIn() {
        JwtTokenService tokenService = new JwtTokenService("", 900, 604800, 900, 604800, false, true, null);

        JwtTokenPair tokenPair = tokenService.issueTokenPair(1L, "USER");

        assertThat(tokenService.parseAccessToken(tokenPair.accessToken())).isPresent();
    }

    @Test
    void requiredRedisTokenStoreRejectsMissingRedisTemplate() {
        assertThatThrownBy(() -> new JwtTokenService(TEST_SECRET, 900, 604800, 900, 604800, false, false, true, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Redis token store is required for JWT revocation and refresh-token state");
    }

    @Test
    void issueAndParseAccessAndRefreshTokens() {
        JwtTokenService tokenService = new JwtTokenService(TEST_SECRET, 30, 60, 30, 60, false, null);

        JwtTokenPair tokenPair = tokenService.issueTokenPair(1L, "USER");

        Optional<AuthenticatedAccessToken> accessToken = tokenService.parseAccessToken(tokenPair.accessToken());
        Optional<AuthenticatedRefreshToken> refreshToken = tokenService.parseRefreshToken(tokenPair.refreshToken());

        assertThat(accessToken).isPresent();
        assertThat(accessToken.get().userId()).isEqualTo(1L);
        assertThat(accessToken.get().authorities()).containsExactly("ROLE_USER");
        assertThat(refreshToken).isPresent();
        assertThat(refreshToken.get().userId()).isEqualTo(1L);
        assertThat(refreshToken.get().authorities()).containsExactly("ROLE_USER");
    }

    @Test
    void tokenTypeMustMatchParser() {
        JwtTokenService tokenService = new JwtTokenService(TEST_SECRET, 30, 60, 30, 60, false, null);

        JwtTokenPair tokenPair = tokenService.issueTokenPair(1L, "USER");

        assertThat(tokenService.parseAccessToken(tokenPair.refreshToken())).isEmpty();
        assertThat(tokenService.parseRefreshToken(tokenPair.accessToken())).isEmpty();
    }

    @Test
    void blankAndMalformedTokensAreRejected() {
        JwtTokenService tokenService = new JwtTokenService(TEST_SECRET, 30, 60, 30, 60, false, null);

        assertThat(tokenService.parseAccessToken(" ")).isEmpty();
        assertThat(tokenService.parseRefreshToken("not-a-jwt")).isEmpty();
    }

    @Test
    void issueAndRotateTokensPreservesPermissionAuthorities() {
        JwtTokenService tokenService = new JwtTokenService(TEST_SECRET, 30, 60, 30, 60, false, null);

        JwtTokenPair firstPair = tokenService.issueTokenPair(1L, "USER", List.of("ROLE_USER", "ORDER_CREATE"));
        JwtTokenPair refreshedPair = tokenService
                .rotateRefreshToken(firstPair.refreshToken())
                .orElseThrow(() -> new AssertionError("Refresh token rotation should succeed"));

        assertThat(tokenService.parseAccessToken(firstPair.accessToken()))
                .get()
                .extracting(AuthenticatedAccessToken::authorities)
                .isEqualTo(List.of("ROLE_USER", "ORDER_CREATE"));
        assertThat(tokenService.parseAccessToken(refreshedPair.accessToken()))
                .get()
                .extracting(AuthenticatedAccessToken::authorities)
                .isEqualTo(List.of("ROLE_USER", "ORDER_CREATE"));
    }

    @Test
    void rotateRefreshTokenInvalidatesPreviousRefreshToken() {
        JwtTokenService tokenService = new JwtTokenService(TEST_SECRET, 30, 60, 30, 60, false, null);

        JwtTokenPair firstPair = tokenService.issueTokenPair(1L, "USER");

        JwtTokenPair refreshedPair = tokenService
                .rotateRefreshToken(firstPair.refreshToken())
                .orElseThrow(() -> new AssertionError("Refresh token rotation should succeed"));

        assertThat(refreshedPair.refreshToken()).isNotEqualTo(firstPair.refreshToken());
        assertThat(tokenService.parseRefreshToken(firstPair.refreshToken())).isEmpty();
    }

    @Test
    void explicitRefreshTokenRevocationInvalidatesRefreshToken() {
        JwtTokenService tokenService = new JwtTokenService(TEST_SECRET, 30, 60, 30, 60, false, null);
        JwtTokenPair tokenPair = tokenService.issueTokenPair(1L, "USER");
        AuthenticatedRefreshToken refreshToken = tokenService
                .parseRefreshToken(tokenPair.refreshToken())
                .orElseThrow(() -> new AssertionError("refresh token should parse"));

        tokenService.revokeRefreshToken(refreshToken);
        tokenService.revokeRefreshToken(null);

        assertThat(tokenService.parseRefreshToken(tokenPair.refreshToken())).isEmpty();
    }

    @Test
    void refreshTokenReplayRevokesOutstandingUserTokens() {
        JwtTokenService tokenService = new JwtTokenService(TEST_SECRET, 30, 60, 30, 60, false, null);

        JwtTokenPair firstPair = tokenService.issueTokenPair(1L, "USER");
        JwtTokenPair refreshedPair = tokenService
                .rotateRefreshToken(firstPair.refreshToken())
                .orElseThrow(() -> new AssertionError("Refresh token rotation should succeed"));

        assertThat(tokenService.revokeUserTokensForRefreshTokenReuse(firstPair.refreshToken()))
                .isTrue();

        assertThat(tokenService.parseAccessToken(refreshedPair.accessToken())).isEmpty();
        assertThat(tokenService.parseRefreshToken(refreshedPair.refreshToken())).isEmpty();
    }

    @Test
    void malformedRefreshTokenReplayDoesNotRevokeUserTokens() {
        JwtTokenService tokenService = new JwtTokenService(TEST_SECRET, 30, 60, 30, 60, false, null);
        JwtTokenPair tokenPair = tokenService.issueTokenPair(1L, "USER");

        assertThat(tokenService.revokeUserTokensForRefreshTokenReuse("not-a-token"))
                .isFalse();

        assertThat(tokenService.parseAccessToken(tokenPair.accessToken())).isPresent();
        assertThat(tokenService.parseRefreshToken(tokenPair.refreshToken())).isPresent();
    }

    @Test
    void accessTokenReplayDoesNotTriggerRefreshReuseRevocation() {
        JwtTokenService tokenService = new JwtTokenService(TEST_SECRET, 30, 60, 30, 60, false, null);
        JwtTokenPair tokenPair = tokenService.issueTokenPair(1L, "USER");

        assertThat(tokenService.revokeUserTokensForRefreshTokenReuse(tokenPair.accessToken()))
                .isFalse();

        assertThat(tokenService.parseAccessToken(tokenPair.accessToken())).isPresent();
        assertThat(tokenService.parseRefreshToken(tokenPair.refreshToken())).isPresent();
    }

    @Test
    void rotateParsedRefreshTokenReissuesCurrentAuthorities() {
        JwtTokenService tokenService = new JwtTokenService(TEST_SECRET, 30, 60, 30, 60, false, null);

        JwtTokenPair firstPair = tokenService.issueTokenPair(1L, "USER", List.of("ROLE_USER", "ORDER_CREATE"));
        AuthenticatedRefreshToken refreshToken = tokenService
                .parseRefreshToken(firstPair.refreshToken())
                .orElseThrow(() -> new AssertionError("Refresh token should parse"));

        JwtTokenPair refreshedPair =
                tokenService.rotateRefreshToken(refreshToken, "USER", List.of("ROLE_USER", "ORDER_READ_OWN"));

        assertThat(tokenService.parseRefreshToken(firstPair.refreshToken())).isEmpty();
        assertThat(tokenService.parseAccessToken(refreshedPair.accessToken()))
                .get()
                .extracting(AuthenticatedAccessToken::authorities)
                .isEqualTo(List.of("ROLE_USER", "ORDER_READ_OWN"));
    }

    @Test
    void requiredRedisTokenStorePersistsRefreshStateInRedis() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> redisValues = mockRedisValues(redisTemplate);
        JwtTokenService tokenService = redisRequiredTokenService(redisTemplate);

        JwtTokenPair firstPair = tokenService.issueTokenPair(1L, "USER", List.of("ROLE_USER", "ORDER_CREATE"));
        String firstRefreshKey = "jwt:refresh:" + firstPair.refreshTokenId();
        when(redisTemplate.hasKey(firstRefreshKey)).thenReturn(true);

        assertThat(tokenService.parseRefreshToken(firstPair.refreshToken())).isPresent();
        JwtTokenPair refreshedPair = tokenService
                .rotateRefreshToken(firstPair.refreshToken())
                .orElseThrow(() -> new AssertionError("Refresh token rotation should succeed"));

        verify(redisValues).set(eq(firstRefreshKey), eq("1"), any(Duration.class));
        verify(redisTemplate).delete(firstRefreshKey);
        verify(redisValues).set(eq("jwt:refresh:" + refreshedPair.refreshTokenId()), eq("1"), any(Duration.class));
    }

    @Test
    void requiredRedisTokenStoreFailsClosedWhenRedisReadFails() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        mockRedisValues(redisTemplate);
        JwtTokenService tokenService = redisRequiredTokenService(redisTemplate);

        JwtTokenPair tokenPair = tokenService.issueTokenPair(1L, "USER");
        when(redisTemplate.hasKey("jwt:revoked:access:" + tokenPair.accessTokenId()))
                .thenThrow(new RuntimeException("redis unavailable"));

        assertThat(tokenService.parseAccessToken(tokenPair.accessToken())).isEmpty();
    }

    @Test
    void requiredRedisTokenStoreRejectsAccessTokenRevokedInRedis() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> redisValues = mockRedisValues(redisTemplate);
        JwtTokenService tokenService = redisRequiredTokenService(redisTemplate);
        JwtTokenPair tokenPair = tokenService.issueTokenPair(1L, "USER");
        when(redisValues.get("jwt:revoked:user:1")).thenReturn(null);
        when(redisTemplate.hasKey("jwt:revoked:access:" + tokenPair.accessTokenId()))
                .thenReturn(true);

        assertThat(tokenService.parseAccessToken(tokenPair.accessToken())).isEmpty();
    }

    @Test
    void optionalRedisRevokedAccessTokenIsHonored() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        mockRedisValues(redisTemplate);
        JwtTokenService tokenService = new JwtTokenService(TEST_SECRET, 30, 60, 30, 60, false, redisTemplate);
        JwtTokenPair tokenPair = tokenService.issueTokenPair(1L, "USER");
        when(redisTemplate.hasKey("jwt:revoked:access:" + tokenPair.accessTokenId()))
                .thenReturn(true);

        assertThat(tokenService.parseAccessToken(tokenPair.accessToken())).isEmpty();
    }

    @Test
    void optionalRedisUserRevocationIsCachedAndHonored() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> redisValues = mockRedisValues(redisTemplate);
        JwtTokenService tokenService = new JwtTokenService(TEST_SECRET, 30, 60, 30, 60, false, redisTemplate);
        JwtTokenPair tokenPair = tokenService.issueTokenPair(1L, "USER");
        when(redisValues.get("jwt:revoked:user:1"))
                .thenReturn(Long.toString(Instant.now().plusSeconds(60).toEpochMilli()));

        assertThat(tokenService.parseAccessToken(tokenPair.accessToken())).isEmpty();
    }

    @Test
    void requiredRedisTokenStoreFailsClosedWhenUserRevocationReadFails() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> redisValues = mockRedisValues(redisTemplate);
        JwtTokenService tokenService = redisRequiredTokenService(redisTemplate);
        JwtTokenPair tokenPair = tokenService.issueTokenPair(1L, "USER");
        when(redisValues.get("jwt:revoked:user:1")).thenThrow(new RuntimeException("redis unavailable"));

        assertThat(tokenService.parseAccessToken(tokenPair.accessToken())).isEmpty();
    }

    @Test
    void requiredRedisTokenStoreRevokesTokensWhenRedisStateReadFails() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> redisValues = mockRedisValues(redisTemplate);
        JwtTokenService tokenService = redisRequiredTokenService(redisTemplate);

        JwtTokenPair tokenPair = tokenService.issueTokenPair(1L, "USER");
        String accessRevocationKey = "jwt:revoked:access:" + tokenPair.accessTokenId();
        String refreshKey = "jwt:refresh:" + tokenPair.refreshTokenId();
        when(redisTemplate.hasKey(accessRevocationKey)).thenThrow(new RuntimeException("redis unavailable"));
        when(redisTemplate.hasKey(refreshKey)).thenThrow(new RuntimeException("redis unavailable"));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(
                new Cookie("access_token", tokenPair.accessToken()),
                new Cookie("refresh_token", tokenPair.refreshToken()));
        MockHttpServletResponse response = new MockHttpServletResponse();

        tokenService.revokeTokens(request, response);

        verify(redisValues).setIfAbsent(eq(accessRevocationKey), eq("1"), any(Duration.class));
        verify(redisTemplate).delete(refreshKey);
        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE))
                .anySatisfy(
                        cookie -> assertThat(cookie).contains("access_token=").contains("Max-Age=0"))
                .anySatisfy(
                        cookie -> assertThat(cookie).contains("refresh_token=").contains("Max-Age=0"));
    }

    @Test
    void requiredRedisTokenStoreFailsClosedWhenLogoutAccessRevocationWriteFails() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> redisValues = mockRedisValues(redisTemplate);
        JwtTokenService tokenService = redisRequiredTokenService(redisTemplate);
        JwtTokenPair tokenPair = tokenService.issueTokenPair(1L, "USER");
        when(redisValues.setIfAbsent(startsWith("jwt:revoked:access:"), eq("1"), any(Duration.class)))
                .thenThrow(new RuntimeException("redis unavailable"));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("access_token", tokenPair.accessToken()));

        assertThatThrownBy(() -> tokenService.revokeTokens(request, new MockHttpServletResponse()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Redis token store is required for JWT access-token revocation");
    }

    @Test
    void requiredRedisTokenStoreFailsClosedWhenRefreshRevocationDeleteFails() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        mockRedisValues(redisTemplate);
        JwtTokenService tokenService = redisRequiredTokenService(redisTemplate);
        JwtTokenPair tokenPair = tokenService.issueTokenPair(1L, "USER");
        when(redisTemplate.hasKey("jwt:refresh:" + tokenPair.refreshTokenId())).thenReturn(true);
        doThrow(new RuntimeException("redis unavailable"))
                .when(redisTemplate)
                .delete("jwt:refresh:" + tokenPair.refreshTokenId());

        assertThatThrownBy(() -> tokenService.rotateRefreshToken(tokenPair.refreshToken()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Redis token store is required for JWT refresh-token revocation");
    }

    @Test
    void requiredRedisTokenStoreRejectsIssuedTokensWhenRedisWriteFails() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> redisValues = mockRedisValues(redisTemplate);
        JwtTokenService tokenService = redisRequiredTokenService(redisTemplate);
        doThrow(new RuntimeException("redis unavailable"))
                .when(redisValues)
                .set(startsWith("jwt:refresh:"), eq("1"), any(Duration.class));

        assertThatThrownBy(() -> tokenService.issueTokenPair(1L, "USER"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Redis token store is required for JWT refresh-token storage");
    }

    @Test
    void requiredRedisTokenStoreFailsClosedWhenUserRevocationWriteFails() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> redisValues = mockRedisValues(redisTemplate);
        JwtTokenService tokenService = redisRequiredTokenService(redisTemplate);
        doThrow(new RuntimeException("redis unavailable"))
                .when(redisValues)
                .set(eq("jwt:revoked:user:1"), anyString(), any(Duration.class));

        assertThatThrownBy(() -> tokenService.revokeUserTokensIssuedBefore(1L, Instant.now()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Redis token store is required for JWT user-token revocation");
    }

    @Test
    void revokeAccessTokenRejectsTokenAfterLogout() {
        JwtTokenService tokenService = new JwtTokenService(TEST_SECRET, 30, 60, 30, 60, false, null);

        JwtTokenPair tokenPair = tokenService.issueTokenPair(1L, "USER");
        String accessToken = tokenPair.accessToken();

        tokenService.revokeAccessToken(accessToken);

        assertThat(tokenService.parseAccessToken(accessToken)).isEmpty();
    }

    @Test
    void revokeAccessTokenIgnoresRefreshTokens() {
        JwtTokenService tokenService = new JwtTokenService(TEST_SECRET, 30, 60, 30, 60, false, null);
        JwtTokenPair tokenPair = tokenService.issueTokenPair(1L, "USER");

        tokenService.revokeAccessToken(tokenPair.refreshToken());

        assertThat(tokenService.parseAccessToken(tokenPair.accessToken())).isPresent();
        assertThat(tokenService.parseRefreshToken(tokenPair.refreshToken())).isPresent();
    }

    @Test
    void tokenCookiesAreHttpOnlySecureAndSameSiteLaxWhenSecureCookiesAreEnabled() {
        JwtTokenService tokenService = new JwtTokenService(TEST_SECRET, 900, 604800, 900, 604800, true, null);
        MockHttpServletResponse response = new MockHttpServletResponse();
        JwtTokenPair tokenPair =
                new JwtTokenPair("access-token", "refresh-token", "access-id", "refresh-id", 900, 604800);

        tokenService.applyTokenCookies(response, tokenPair);

        List<String> setCookies = response.getHeaders(HttpHeaders.SET_COOKIE);
        assertThat(setCookies).hasSize(2);
        assertThat(setCookies.get(0))
                .contains("access_token=access-token")
                .contains("Max-Age=900")
                .contains("Path=/")
                .contains("Secure")
                .contains("HttpOnly")
                .contains("SameSite=Lax");
        assertThat(setCookies.get(1))
                .contains("refresh_token=refresh-token")
                .contains("Max-Age=604800")
                .contains("Path=/")
                .contains("Secure")
                .contains("HttpOnly")
                .contains("SameSite=Lax");
    }

    @Test
    void clearTokenCookiesExpiresBothJwtCookies() {
        JwtTokenService tokenService = new JwtTokenService(TEST_SECRET, 900, 604800, 900, 604800, true, null);
        MockHttpServletResponse response = new MockHttpServletResponse();

        tokenService.clearTokenCookies(response);

        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE))
                .anySatisfy(
                        cookie -> assertThat(cookie).contains("access_token=").contains("Max-Age=0"))
                .anySatisfy(
                        cookie -> assertThat(cookie).contains("refresh_token=").contains("Max-Age=0"));
    }

    @Test
    void resolveAccessTokenAcceptsBearerOrCookieButIgnoresLegacyHeader() {
        JwtTokenService tokenService = new JwtTokenService(TEST_SECRET, 30, 60, 30, 60, false, null);

        MockHttpServletRequest legacyHeaderRequest = new MockHttpServletRequest();
        legacyHeaderRequest.addHeader("X-Access-Token", "legacy-token");
        assertThat(tokenService.resolveAccessToken(legacyHeaderRequest)).isEmpty();

        MockHttpServletRequest bearerRequest = new MockHttpServletRequest();
        bearerRequest.addHeader(HttpHeaders.AUTHORIZATION, "Bearer bearer-token");
        assertThat(tokenService.resolveAccessToken(bearerRequest)).contains("bearer-token");

        MockHttpServletRequest cookieRequest = new MockHttpServletRequest();
        cookieRequest.setCookies(new Cookie("access_token", "cookie-token"));
        assertThat(tokenService.resolveAccessToken(cookieRequest)).contains("cookie-token");
    }

    @Test
    void resolveRefreshTokenReadsCookieOnly() {
        JwtTokenService tokenService = new JwtTokenService(TEST_SECRET, 30, 60, 30, 60, false, null);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("refresh_token", "refresh-cookie"));

        assertThat(tokenService.resolveRefreshToken(request)).isEqualTo("refresh-cookie");
        assertThat(tokenService.resolveRefreshToken(new MockHttpServletRequest()))
                .isNull();
    }

    @Test
    void revokeUserTokensRejectsExistingAccessAndRefreshTokens() {
        JwtTokenService tokenService = new JwtTokenService(TEST_SECRET, 30, 60, 30, 60, false, null);

        JwtTokenPair tokenPair = tokenService.issueTokenPair(1L, "USER");

        tokenService.revokeUserTokensIssuedBefore(1L, Instant.now().plusSeconds(1));

        assertThat(tokenService.parseAccessToken(tokenPair.accessToken())).isEmpty();
        assertThat(tokenService.parseRefreshToken(tokenPair.refreshToken())).isEmpty();
    }

    @Test
    void revokeUserTokensWithResponseRevokesAndClearsCookies() {
        JwtTokenService tokenService = new JwtTokenService(TEST_SECRET, 30, 60, 30, 60, true, null);
        JwtTokenPair tokenPair = tokenService.issueTokenPair(1L, "USER");
        MockHttpServletResponse response = new MockHttpServletResponse();

        tokenService.revokeUserTokens(1L, response);

        assertThat(tokenService.parseAccessToken(tokenPair.accessToken())).isEmpty();
        assertThat(tokenService.parseRefreshToken(tokenPair.refreshToken())).isEmpty();
        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE))
                .anySatisfy(
                        cookie -> assertThat(cookie).contains("access_token=").contains("Max-Age=0"))
                .anySatisfy(
                        cookie -> assertThat(cookie).contains("refresh_token=").contains("Max-Age=0"));
    }

    @Test
    void revokeTokensIgnoresTokensInWrongCookieSlotsButStillClearsCookies() {
        JwtTokenService tokenService = new JwtTokenService(TEST_SECRET, 30, 60, 30, 60, false, null);
        JwtTokenPair tokenPair = tokenService.issueTokenPair(1L, "USER");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(
                new Cookie("access_token", tokenPair.refreshToken()),
                new Cookie("refresh_token", tokenPair.accessToken()));
        MockHttpServletResponse response = new MockHttpServletResponse();

        tokenService.revokeTokens(request, response);

        assertThat(tokenService.parseAccessToken(tokenPair.accessToken())).isPresent();
        assertThat(tokenService.parseRefreshToken(tokenPair.refreshToken())).isPresent();
        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).hasSize(2);
    }

    @Test
    void invalidUserRevocationArgumentsAreIgnored() {
        JwtTokenService tokenService = new JwtTokenService(TEST_SECRET, 30, 60, 30, 60, false, null);
        JwtTokenPair tokenPair = tokenService.issueTokenPair(1L, "USER");

        tokenService.revokeUserTokensIssuedBefore(null, Instant.now());
        tokenService.revokeUserTokensIssuedBefore(0L, Instant.now());
        tokenService.revokeUserTokensIssuedBefore(1L, null);

        assertThat(tokenService.parseAccessToken(tokenPair.accessToken())).isPresent();
    }

    private static JwtTokenService redisRequiredTokenService(StringRedisTemplate redisTemplate) {
        return new JwtTokenService(TEST_SECRET, 30, 60, 30, 60, false, false, true, redisTemplate);
    }

    @SuppressWarnings("unchecked")
    private static ValueOperations<String, String> mockRedisValues(StringRedisTemplate redisTemplate) {
        ValueOperations<String, String> redisValues = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(redisValues);
        return redisValues;
    }
}
