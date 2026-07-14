package com.example.monkey.user.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.shared.application.security.SessionTokenPair;
import com.example.monkey.shared.domain.security.JwtTokenPair;
import com.example.monkey.user.domain.RefreshTokenReuseException;
import com.example.monkey.user.domain.SessionTokenService;
import com.example.monkey.user.domain.SessionTokenService.AuthenticatedAccessToken;
import com.example.monkey.user.domain.SessionTokenService.AuthenticatedRefreshToken;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
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
    void tenantClaimIsParsedAndPreservedOnRefreshRotation() {
        JwtTokenService tokenService = new JwtTokenService(TEST_SECRET, 30, 60, 30, 60, false, null);

        JwtTokenPair tokenPair = tokenService.issueTokenPair(1L, "USER", List.of("ROLE_USER"), 200L);
        JwtTokenPair refreshedPair = tokenService
                .rotateRefreshToken(tokenPair.refreshToken())
                .orElseThrow(() -> new AssertionError("Refresh token rotation should succeed"));

        assertThat(tokenService.parseAccessToken(tokenPair.accessToken()))
                .get()
                .extracting(AuthenticatedAccessToken::tenantId)
                .isEqualTo(200L);
        assertThat(tokenService.parseAccessToken(refreshedPair.accessToken()))
                .get()
                .extracting(AuthenticatedAccessToken::tenantId)
                .isEqualTo(200L);
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
    @ExtendWith(OutputCaptureExtension.class)
    void malformedJwtTokensAreCountedWithoutLeakingRawToken(CapturedOutput output) {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        JwtTokenService tokenService =
                new JwtTokenService(TEST_SECRET, 30, 60, 30, 60, false, false, false, null, meterRegistry);
        String malformedToken = "not-a-jwt";

        assertThat(tokenService.parseAccessToken(malformedToken)).isEmpty();

        assertThat(meterRegistry
                        .get("auth.jwt.parse.failure")
                        .tag("reason", "malformed")
                        .counter()
                        .count())
                .isEqualTo(1.0d);
        assertThat(output).doesNotContain(malformedToken);
    }

    @Test
    void jwtParseFailuresUseBoundedSignatureExpiryAndRevocationReasons() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        MutableClock clock = new MutableClock(Instant.parse("2026-07-14T00:00:00Z"));
        JwtTokenService tokenService = meteredTokenService(clock, meterRegistry);
        JwtTokenService foreignIssuer =
                new JwtTokenService("different-unit-test-secret-key-long-enough-for-hmac", 30, 60, 30, 60, false, null);

        JwtTokenPair foreignPair = foreignIssuer.issueTokenPair(1L, "USER");
        assertThat(tokenService.parseAccessToken(foreignPair.accessToken())).isEmpty();

        JwtTokenPair expiringPair = tokenService.issueTokenPair(1L, "USER");
        clock.advance(Duration.ofSeconds(61));
        assertThat(tokenService.parseAccessToken(expiringPair.accessToken())).isEmpty();

        JwtTokenPair revokedPair = tokenService.issueTokenPair(1L, "USER");
        tokenService.revokeAccessToken(revokedPair.accessToken());
        assertThat(tokenService.parseAccessToken(revokedPair.accessToken())).isEmpty();

        assertThat(parseFailureCount(meterRegistry, "invalid-signature")).isEqualTo(1.0d);
        assertThat(parseFailureCount(meterRegistry, "expired")).isEqualTo(1.0d);
        assertThat(parseFailureCount(meterRegistry, "revoked")).isEqualTo(1.0d);
    }

    @Test
    void signedJwtWithoutExpirationIsMalformedRatherThanExpired() throws Exception {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        Instant issuedAt = Instant.parse("2026-07-14T00:00:00Z");
        JwtTokenService tokenService = meteredTokenService(Clock.fixed(issuedAt, ZoneOffset.UTC), meterRegistry);

        assertThat(tokenService.parseAccessToken(signedTokenWithoutExpiration(issuedAt)))
                .isEmpty();

        assertThat(parseFailureCountOrZero(meterRegistry, "malformed")).isEqualTo(1.0d);
        assertThat(parseFailureCountOrZero(meterRegistry, "expired")).isZero();
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void unexpectedJwtFailuresLogOnlyTheExceptionClass(CapturedOutput output) {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        JwtTokenService issuer = new JwtTokenService(TEST_SECRET, 30, 60, 30, 60, false, null);
        String rawToken = issuer.issueTokenPair(1L, "USER").accessToken();
        JwtTokenService tokenService = meteredTokenService(new ThrowingClock(), meterRegistry);

        assertThat(tokenService.parseAccessToken(rawToken)).isEmpty();

        assertThat(parseFailureCount(meterRegistry, "unexpected")).isEqualTo(1.0d);
        assertThat(output)
                .contains("IllegalStateException")
                .doesNotContain(rawToken)
                .doesNotContain("clock backend detail");
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
    void concurrentRefreshRotationReturnsOneTokenPairWithinGrace() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-12T06:00:00Z"));
        JwtTokenService tokenService = tokenService(clock, Duration.ofSeconds(5));
        JwtTokenPair firstPair = tokenService.issueTokenPair(1L, "USER");
        AuthenticatedRefreshToken parsed = tokenService
                .parseRefreshToken(firstPair.refreshToken())
                .orElseThrow(() -> new AssertionError("Refresh token should parse"));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<JwtTokenPair> first = executor.submit(() -> {
                start.await();
                return tokenService.rotateRefreshToken(parsed, "USER", List.of("ROLE_USER"));
            });
            Future<JwtTokenPair> second = executor.submit(() -> {
                start.await();
                return tokenService.rotateRefreshToken(parsed, "USER", List.of("ROLE_USER"));
            });
            start.countDown();

            JwtTokenPair firstResult = first.get();
            JwtTokenPair secondResult = second.get();

            assertThat(secondResult).isEqualTo(firstResult);
            assertThat(tokenService.parseRefreshToken(firstPair.refreshToken())).isEmpty();
            assertThat(tokenService.recoverRefreshTokenRotation(firstPair.refreshToken()))
                    .get()
                    .extracting(recovery -> recovery.tokenPair().refreshToken())
                    .isEqualTo(firstResult.refreshToken());
            assertThat(tokenService.revokeUserTokensForRefreshTokenReuse(firstPair.refreshToken()))
                    .isFalse();
            assertThat(tokenService.parseRefreshToken(firstResult.refreshToken()))
                    .isPresent();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void refreshTokenReplayAfterGraceRevokesOutstandingUserTokens() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-12T06:00:00Z"));
        JwtTokenService tokenService = tokenService(clock, Duration.ofSeconds(5));

        JwtTokenPair firstPair = tokenService.issueTokenPair(1L, "USER");
        JwtTokenPair refreshedPair = tokenService
                .rotateRefreshToken(firstPair.refreshToken())
                .orElseThrow(() -> new AssertionError("Refresh token rotation should succeed"));

        clock.advance(Duration.ofSeconds(6));

        assertThat(tokenService.revokeUserTokensForRefreshTokenReuse(firstPair.refreshToken()))
                .isTrue();

        assertThat(tokenService.parseAccessToken(refreshedPair.accessToken())).isEmpty();
        assertThat(tokenService.parseRefreshToken(refreshedPair.refreshToken())).isEmpty();
    }

    @Test
    void parsedRefreshTokenReusedAfterGraceRevokesOutstandingUserTokens() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-12T06:00:00Z"));
        JwtTokenService tokenService = tokenService(clock, Duration.ofSeconds(5));
        JwtTokenPair firstPair = tokenService.issueTokenPair(1L, "USER");
        AuthenticatedRefreshToken parsed = tokenService
                .parseRefreshToken(firstPair.refreshToken())
                .orElseThrow(() -> new AssertionError("Refresh token should parse"));
        JwtTokenPair refreshedPair = tokenService.rotateRefreshToken(parsed, "USER", List.of("ROLE_USER"));
        clock.advance(Duration.ofSeconds(6));

        assertThatThrownBy(() -> tokenService.rotateRefreshToken(parsed, "USER", List.of("ROLE_USER")))
                .isInstanceOf(RefreshTokenReuseException.class);
        assertThat(tokenService.parseAccessToken(refreshedPair.accessToken())).isEmpty();
        assertThat(tokenService.parseRefreshToken(refreshedPair.refreshToken())).isEmpty();
    }

    @Test
    void parsedSuccessorCannotRotateAfterPredecessorReplayRevokesItsGeneration() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-12T06:00:00Z"));
        JwtTokenService tokenService = tokenService(clock, Duration.ofSeconds(5));
        JwtTokenPair predecessor = tokenService.issueTokenPair(1L, "USER");
        JwtTokenPair successor = tokenService
                .rotateRefreshToken(predecessor.refreshToken())
                .orElseThrow(() -> new AssertionError("Refresh token rotation should succeed"));
        AuthenticatedRefreshToken parsedSuccessor = tokenService
                .parseRefreshToken(successor.refreshToken())
                .orElseThrow(() -> new AssertionError("Successor refresh token should parse"));
        clock.advance(Duration.ofSeconds(6));

        assertThat(tokenService.revokeUserTokensForRefreshTokenReuse(predecessor.refreshToken()))
                .isTrue();

        assertThatThrownBy(() -> tokenService.rotateRefreshToken(parsedSuccessor, "USER", List.of("ROLE_USER")))
                .isInstanceOf(RefreshTokenReuseException.class);
        assertThat(tokenService.parseAccessToken(successor.accessToken())).isEmpty();
        assertThat(tokenService.parseRefreshToken(successor.refreshToken())).isEmpty();
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
        verify(redisTemplate, never()).delete(firstRefreshKey);
        verify(redisValues, never())
                .set(eq("jwt:refresh:" + refreshedPair.refreshTokenId()), eq("1"), any(Duration.class));
    }

    @Test
    void redisRotationUsesOneAtomicConsumeGenerationCheckActivationAndRecoveryWrite() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> redisValues = mockRedisValues(redisTemplate);
        List<RedisScript<?>> executedScripts = new ArrayList<>();
        List<List<String>> executedKeys = new ArrayList<>();
        doAnswer(invocation -> {
                    RedisScript<?> script = invocation.getArgument(0);
                    executedScripts.add(script);
                    executedKeys.add(List.copyOf(invocation.getArgument(1)));
                    return String.class.equals(script.getResultType()) ? "ROTATED" : 1L;
                })
                .when(redisTemplate)
                .execute(any(RedisScript.class), anyList(), any(Object[].class));
        JwtTokenService tokenService = redisRequiredTokenService(redisTemplate);
        JwtTokenPair predecessor = tokenService.issueTokenPair(1L, "USER");
        String predecessorKey = "jwt:refresh:" + predecessor.refreshTokenId();
        when(redisTemplate.hasKey(predecessorKey)).thenReturn(true);

        JwtTokenPair successor = tokenService
                .rotateRefreshToken(predecessor.refreshToken())
                .orElseThrow(() -> new AssertionError("Refresh token rotation should succeed"));

        assertThat(executedScripts)
                .singleElement()
                .satisfies(script -> assertThat(script.getScriptAsString())
                        .contains("redis.call('get', KEYS[4])")
                        .contains("redis.call('del', KEYS[1])")
                        .contains("redis.call('psetex', KEYS[2]")
                        .contains("redis.call('psetex', KEYS[3]"));
        assertThat(executedKeys)
                .singleElement()
                .satisfies(keys -> assertThat(keys)
                        .containsExactly(
                                predecessorKey,
                                "jwt:refresh:" + successor.refreshTokenId(),
                                "jwt:refresh:rotation:" + predecessor.refreshTokenId(),
                                "jwt:revoked:user:1"));
        verify(redisValues, never())
                .setIfAbsent(startsWith("jwt:refresh:rotation-lock:"), anyString(), any(Duration.class));
    }

    @Test
    void redisRotationsForDifferentUsersDoNotShareAJvmWideMonitor() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        mockRedisValues(redisTemplate);
        CountDownLatch enteredScript = new CountDownLatch(2);
        CountDownLatch releaseScripts = new CountDownLatch(1);
        doAnswer(invocation -> {
                    RedisScript<?> script = invocation.getArgument(0);
                    if (!String.class.equals(script.getResultType())) {
                        return 1L;
                    }
                    enteredScript.countDown();
                    if (!enteredScript.await(2, TimeUnit.SECONDS)) {
                        throw new AssertionError("Redis refresh rotations were serialized in the JVM");
                    }
                    releaseScripts.await(2, TimeUnit.SECONDS);
                    return "ROTATED";
                })
                .when(redisTemplate)
                .execute(any(RedisScript.class), anyList(), any(Object[].class));
        when(redisTemplate.hasKey(startsWith("jwt:refresh:"))).thenReturn(true);
        JwtTokenService tokenService = redisRequiredTokenService(redisTemplate);
        JwtTokenPair first = tokenService.issueTokenPair(1L, "USER");
        JwtTokenPair second = tokenService.issueTokenPair(2L, "USER");
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<JwtTokenPair> firstRotation = executor.submit(
                    () -> tokenService.rotateRefreshToken(first.refreshToken()).orElseThrow());
            Future<JwtTokenPair> secondRotation = executor.submit(
                    () -> tokenService.rotateRefreshToken(second.refreshToken()).orElseThrow());

            assertThat(enteredScript.await(2, TimeUnit.SECONDS)).isTrue();
            releaseScripts.countDown();
            assertThat(firstRotation.get(2, TimeUnit.SECONDS)).isNotNull();
            assertThat(secondRotation.get(2, TimeUnit.SECONDS)).isNotNull();
        } finally {
            releaseScripts.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void redisRotationRecoveryNeverStoresBearerTokensInPlaintext() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> redisValues = mockRedisValues(redisTemplate);
        List<String> redisPayloads = new ArrayList<>();
        doAnswer(invocation -> {
                    redisPayloads.add(invocation.getArgument(1));
                    return null;
                })
                .when(redisValues)
                .set(anyString(), anyString(), any(Duration.class));
        doAnswer(invocation -> {
                    RedisScript<?> script = invocation.getArgument(0);
                    Object[] arguments = invocation.getArguments();
                    for (int index = 2; index < arguments.length; index++) {
                        Object argument = arguments[index];
                        if (argument instanceof String value) {
                            redisPayloads.add(value);
                        }
                    }
                    return String.class.equals(script.getResultType()) ? "ROTATED" : 1L;
                })
                .when(redisTemplate)
                .execute(any(RedisScript.class), anyList(), any(Object[].class));
        JwtTokenService tokenService = redisRequiredTokenService(redisTemplate);
        JwtTokenPair predecessor = tokenService.issueTokenPair(1L, "USER");
        when(redisTemplate.hasKey("jwt:refresh:" + predecessor.refreshTokenId()))
                .thenReturn(true);

        JwtTokenPair successor = tokenService
                .rotateRefreshToken(predecessor.refreshToken())
                .orElseThrow(() -> new AssertionError("Refresh token rotation should succeed"));

        assertThat(redisPayloads)
                .noneMatch(value -> value.contains(successor.accessToken()))
                .noneMatch(value -> value.contains(successor.refreshToken()))
                .anyMatch(value -> value.startsWith("v1."));
    }

    @Test
    void encryptedRedisRecoveryIsReadableAcrossServiceInstances() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> redisValues = mockRedisValues(redisTemplate);
        AtomicReference<String> encryptedRecovery = new AtomicReference<>();
        doAnswer(invocation -> {
                    RedisScript<?> script = invocation.getArgument(0);
                    if (String.class.equals(script.getResultType())) {
                        Object[] arguments = invocation.getArguments();
                        encryptedRecovery.set((String) arguments[arguments.length - 1]);
                        return "ROTATED";
                    }
                    return 1L;
                })
                .when(redisTemplate)
                .execute(any(RedisScript.class), anyList(), any(Object[].class));
        JwtTokenService firstNode = redisRequiredTokenService(redisTemplate);
        JwtTokenPair predecessor = firstNode.issueTokenPair(1L, "USER");
        when(redisTemplate.hasKey("jwt:refresh:" + predecessor.refreshTokenId()))
                .thenReturn(true);
        JwtTokenPair successor = firstNode
                .rotateRefreshToken(predecessor.refreshToken())
                .orElseThrow(() -> new AssertionError("Refresh token rotation should succeed"));
        when(redisValues.get("jwt:refresh:rotation:" + predecessor.refreshTokenId()))
                .thenAnswer(ignored -> encryptedRecovery.get());
        when(redisTemplate.hasKey("jwt:refresh:" + successor.refreshTokenId())).thenReturn(true);
        JwtTokenService secondNode = redisRequiredTokenService(redisTemplate);

        SessionTokenService.RecoveredRefreshToken recovered = secondNode
                .recoverRefreshTokenRotation(predecessor.refreshToken())
                .orElseThrow(() -> new AssertionError("Encrypted rotation should be recovered"));

        assertThat(encryptedRecovery.get()).startsWith("v1.");
        assertThat(recovered.tokenPair()).isEqualTo(successor);
    }

    @Test
    void tamperedRedisRecoveryCiphertextIsRejected() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> redisValues = mockRedisValues(redisTemplate);
        AtomicReference<String> encryptedRecovery = new AtomicReference<>();
        doAnswer(invocation -> {
                    RedisScript<?> script = invocation.getArgument(0);
                    if (String.class.equals(script.getResultType())) {
                        Object[] arguments = invocation.getArguments();
                        encryptedRecovery.set((String) arguments[arguments.length - 1]);
                        return "ROTATED";
                    }
                    return 1L;
                })
                .when(redisTemplate)
                .execute(any(RedisScript.class), anyList(), any(Object[].class));
        JwtTokenService firstNode = redisRequiredTokenService(redisTemplate);
        JwtTokenPair predecessor = firstNode.issueTokenPair(1L, "USER");
        when(redisTemplate.hasKey("jwt:refresh:" + predecessor.refreshTokenId()))
                .thenReturn(true);
        firstNode
                .rotateRefreshToken(predecessor.refreshToken())
                .orElseThrow(() -> new AssertionError("Refresh token rotation should succeed"));
        String ciphertext = encryptedRecovery.get();
        char replacement = ciphertext.endsWith("A") ? 'B' : 'A';
        String tampered = ciphertext.substring(0, ciphertext.length() - 1) + replacement;
        when(redisValues.get("jwt:refresh:rotation:" + predecessor.refreshTokenId()))
                .thenReturn(tampered);
        JwtTokenService secondNode = redisRequiredTokenService(redisTemplate);

        assertThat(secondNode.recoverRefreshTokenRotation(predecessor.refreshToken()))
                .isEmpty();
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
    void requiredRedisTokenStoreFailsClosedWhenAtomicRotationFails() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        mockRedisValues(redisTemplate);
        JwtTokenService tokenService = redisRequiredTokenService(redisTemplate);
        JwtTokenPair tokenPair = tokenService.issueTokenPair(1L, "USER");
        when(redisTemplate.hasKey("jwt:refresh:" + tokenPair.refreshTokenId())).thenReturn(true);
        doThrow(new RuntimeException("redis unavailable"))
                .when(redisTemplate)
                .execute(any(RedisScript.class), anyList(), any(Object[].class));

        assertThatThrownBy(() -> tokenService.rotateRefreshToken(tokenPair.refreshToken()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Redis token store is required for JWT refresh-token rotation");
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
        SessionTokenPair tokenPair =
                new SessionTokenPair("access-token", "refresh-token", "access-id", "refresh-id", 900, 604800);

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

    private static JwtTokenService tokenService(Clock clock, Duration refreshRotationGrace) {
        return new JwtTokenService(TEST_SECRET, 30, 60, 30, 60, false, false, false, null, refreshRotationGrace, clock);
    }

    private static JwtTokenService meteredTokenService(Clock clock, SimpleMeterRegistry meterRegistry) {
        return new JwtTokenService(
                TEST_SECRET, 30, 60, 30, 60, false, false, false, null, Duration.ofSeconds(5), clock, meterRegistry);
    }

    private static double parseFailureCount(SimpleMeterRegistry meterRegistry, String reason) {
        return meterRegistry
                .get("auth.jwt.parse.failure")
                .tag("reason", reason)
                .counter()
                .count();
    }

    private static double parseFailureCountOrZero(SimpleMeterRegistry meterRegistry, String reason) {
        var counter = meterRegistry
                .find("auth.jwt.parse.failure")
                .tag("reason", reason)
                .counter();
        return counter == null ? 0.0d : counter.count();
    }

    private static String signedTokenWithoutExpiration(Instant issuedAt) throws Exception {
        SignedJWT token = new SignedJWT(
                new JWSHeader(JWSAlgorithm.HS256),
                new JWTClaimsSet.Builder()
                        .jwtID("missing-exp")
                        .subject("1")
                        .claim("role", "USER")
                        .claim("auth", List.of("USER"))
                        .claim("typ", "access")
                        .issueTime(Date.from(issuedAt))
                        .build());
        token.sign(new MACSigner(TEST_SECRET.getBytes(StandardCharsets.UTF_8)));
        return token.serialize();
    }

    @SuppressWarnings("unchecked")
    private static ValueOperations<String, String> mockRedisValues(StringRedisTemplate redisTemplate) {
        ValueOperations<String, String> redisValues = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(redisValues);
        doAnswer(invocation -> {
                    RedisScript<?> script = invocation.getArgument(0);
                    return String.class.equals(script.getResultType()) ? "ROTATED" : 1L;
                })
                .when(redisTemplate)
                .execute(any(RedisScript.class), anyList(), any(Object[].class));
        return redisValues;
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }

    private static final class ThrowingClock extends Clock {

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            throw new IllegalStateException("clock backend detail");
        }
    }
}
