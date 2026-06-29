package com.example.monkey.user.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.monkey.shared.domain.exception.BusinessException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class TurnstileVerifierTest {

    @Test
    void disabledVerifierReturnsFalseWithoutCallingSiteverify() {
        TurnstileVerifier.SiteverifyClient siteverifyClient = request -> {
            throw new AssertionError("disabled verifier must not call Cloudflare");
        };
        TurnstileVerifier verifier =
                new TurnstileVerifier(siteverifyClient, null, false, "secret", "", Duration.ofSeconds(60), false);

        assertThat(verifier.enabled()).isFalse();
        assertThat(verifier.verify("token-disabled", "login", "127.0.0.1")).isFalse();
    }

    @Test
    void verifiesSuccessActionAndHostname() {
        TurnstileVerifier verifier = new TurnstileVerifier(
                request -> {
                    assertThat(request.remoteip()).isEqualTo("203.0.113.10");
                    return new TurnstileVerifier.SiteverifyResponse(
                            true, "2026-06-29T00:00:00Z", "shop.example", List.of(), "login", null);
                },
                null,
                true,
                "secret",
                "shop.example",
                Duration.ofSeconds(60),
                false);

        assertThat(verifier.verify("token-1", "login", "203.0.113.10")).isTrue();
    }

    @Test
    void acceptsAnyHostnameWhenNoExpectedHostnameIsConfigured() {
        TurnstileVerifier verifier = new TurnstileVerifier(
                request -> new TurnstileVerifier.SiteverifyResponse(
                        true, "2026-06-29T00:00:00Z", "other.example", List.of(), "login", null),
                null,
                true,
                "secret",
                "",
                Duration.ofSeconds(60),
                false);

        assertThat(verifier.verify("token-host-any", "login", "127.0.0.1")).isTrue();
    }

    @Test
    void rejectsMismatchedHostname() {
        TurnstileVerifier verifier = new TurnstileVerifier(
                request -> new TurnstileVerifier.SiteverifyResponse(
                        true, "2026-06-29T00:00:00Z", "evil.example", List.of(), "login", null),
                null,
                true,
                "secret",
                "shop.example",
                Duration.ofSeconds(60),
                false);

        assertThat(verifier.verify("token-host-bad", "login", "127.0.0.1")).isFalse();
    }

    @Test
    void rejectsMismatchedAction() {
        TurnstileVerifier verifier = new TurnstileVerifier(
                request -> new TurnstileVerifier.SiteverifyResponse(
                        true, null, "shop.example", List.of(), "register", null),
                null,
                true,
                "secret",
                "shop.example",
                Duration.ofSeconds(60),
                false);

        assertThat(verifier.verify("token-2", "login", "127.0.0.1")).isFalse();
    }

    @Test
    void rejectsMissingAndOversizedTokensWithoutCallingSiteverify() {
        TurnstileVerifier.SiteverifyClient siteverifyClient = mock(TurnstileVerifier.SiteverifyClient.class);
        TurnstileVerifier verifier =
                new TurnstileVerifier(siteverifyClient, null, true, "secret", "", Duration.ofSeconds(60), false);

        assertThat(verifier.verify("", "login", "127.0.0.1")).isFalse();
        assertThat(verifier.verify("x".repeat(TurnstileVerifier.MAX_TOKEN_LENGTH + 1), "login", "127.0.0.1"))
                .isFalse();

        verifyNoInteractions(siteverifyClient);
    }

    @Test
    void consumedTokenCannotBeRetriedEvenAfterFailure() {
        TurnstileVerifier verifier = new TurnstileVerifier(
                request -> new TurnstileVerifier.SiteverifyResponse(
                        false, null, "shop.example", List.of("invalid-input-response"), "login", null),
                null,
                true,
                "secret",
                "shop.example",
                Duration.ofSeconds(60),
                false);

        assertThat(verifier.verify("token-3", "login", "127.0.0.1")).isFalse();
        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> verifier.verify("token-3", "login", "127.0.0.1"))
                .withMessage(TurnstileVerifier.TOKEN_REPLAY_MESSAGE);
    }

    @Test
    void requiredRedisStateRejectsReplayedRedisToken() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mockRedisValues(redisTemplate);
        when(values.setIfAbsent(startsWith(TurnstileVerifier.REDIS_TOKEN_PREFIX), eq("1"), eq(Duration.ofSeconds(60))))
                .thenReturn(false);
        TurnstileVerifier verifier = new TurnstileVerifier(
                request ->
                        new TurnstileVerifier.SiteverifyResponse(true, null, "shop.example", List.of(), "login", null),
                redisTemplate,
                true,
                "secret",
                "shop.example",
                Duration.ofSeconds(60),
                true);

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> verifier.verify("token-redis-replay", "login", "127.0.0.1"))
                .withMessage(TurnstileVerifier.TOKEN_REPLAY_MESSAGE);
    }

    @Test
    void requiredRedisStateFailsClosedWhenReplayStoreIsUnavailable() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mockRedisValues(redisTemplate);
        doThrow(new RuntimeException("redis unavailable"))
                .when(values)
                .setIfAbsent(any(), eq("1"), any(Duration.class));
        TurnstileVerifier verifier = new TurnstileVerifier(
                request ->
                        new TurnstileVerifier.SiteverifyResponse(true, null, "shop.example", List.of(), "login", null),
                redisTemplate,
                true,
                "secret",
                "shop.example",
                Duration.ofSeconds(60),
                true);

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> verifier.verify("token-redis-fail", "login", "127.0.0.1"))
                .withMessage(TurnstileVerifier.TOKEN_INVALID_MESSAGE);
    }

    @Test
    void optionalRedisStateFallsBackOpenForReplayStoreOutage() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mockRedisValues(redisTemplate);
        doThrow(new RuntimeException("redis unavailable"))
                .when(values)
                .setIfAbsent(any(), eq("1"), any(Duration.class));
        TurnstileVerifier verifier = new TurnstileVerifier(
                request ->
                        new TurnstileVerifier.SiteverifyResponse(true, null, "shop.example", List.of(), "login", null),
                redisTemplate,
                true,
                "secret",
                "shop.example",
                Duration.ofSeconds(60),
                false);

        assertThat(verifier.verify("token-redis-optional", "login", "127.0.0.1"))
                .isTrue();
    }

    @Test
    void missingSecretFailsClosedWhenEnabled() {
        TurnstileVerifier verifier =
                new TurnstileVerifier(request -> null, null, true, "", "", Duration.ofSeconds(60), false);

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> verifier.verify("token-4", "login", "127.0.0.1"))
                .withMessage(TurnstileVerifier.TOKEN_CONFIG_MESSAGE);
    }

    @SuppressWarnings("unchecked")
    private static ValueOperations<String, String> mockRedisValues(StringRedisTemplate redisTemplate) {
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        return values;
    }
}
