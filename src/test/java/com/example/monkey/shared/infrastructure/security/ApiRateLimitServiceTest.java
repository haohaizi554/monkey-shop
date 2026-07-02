package com.example.monkey.shared.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.shared.domain.security.ApiRateLimiter.RateLimitDecision;
import com.example.monkey.shared.domain.security.RateLimitPolicy;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class ApiRateLimitServiceTest {

    @Test
    void disabledServiceAndNullPolicyAllowRequests() {
        ApiRateLimitService disabledService = new ApiRateLimitService(null, false, false, Duration.ofHours(24));
        ApiRateLimitService enabledService = new ApiRateLimitService(null, true, false, Duration.ofHours(24));

        assertThat(disabledService
                        .consume(RateLimitPolicy.LOGIN, "203.0.113.10", "alice")
                        .allowed())
                .isTrue();
        assertThat(enabledService.consume(null, "203.0.113.10", "alice").allowed())
                .isTrue();
    }

    @Test
    void localBucketRejectsWhenPolicyCapacityIsExceeded() {
        ApiRateLimitService service = new ApiRateLimitService(null, true, false, Duration.ofHours(24));

        for (int i = 0; i < RateLimitPolicy.LOGIN.capacity(); i++) {
            assertThat(service.consume(RateLimitPolicy.LOGIN, "203.0.113.10", "alice")
                            .allowed())
                    .isTrue();
        }

        RateLimitDecision rejected = service.consume(RateLimitPolicy.LOGIN, "203.0.113.10", "alice");
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.policy()).isEqualTo(RateLimitPolicy.LOGIN);
        assertThat(rejected.retryAfterSeconds()).isPositive();
    }

    @Test
    void anonymousRequestsFromDifferentIpsDoNotShareUserOrEndpointBuckets() {
        ApiRateLimitService service = new ApiRateLimitService(null, true, false, Duration.ofHours(24));

        for (int i = 0; i < RateLimitPolicy.SEARCH.capacity(); i++) {
            assertThat(service.consume(RateLimitPolicy.SEARCH, "203.0.113.10", "anonymous")
                            .allowed())
                    .isTrue();
        }

        assertThat(service.consume(RateLimitPolicy.SEARCH, "203.0.113.11", "anonymous")
                        .allowed())
                .isTrue();
        assertThat(service.consume(RateLimitPolicy.SEARCH, "203.0.113.10", "anonymous")
                        .allowed())
                .isFalse();
    }

    @Test
    void honeypotBlockIsRememberedLocally() {
        ApiRateLimitService service = new ApiRateLimitService(null, true, false, Duration.ofHours(24));

        service.blockForHoneypot("203.0.113.66");

        assertThat(service.isBlocked("203.0.113.66")).isTrue();
    }

    @Test
    void expiredLocalHoneypotBlockIsRemoved() throws Exception {
        ApiRateLimitService service = new ApiRateLimitService(null, true, false, Duration.ofMillis(1));

        service.blockForHoneypot("203.0.113.66");
        Thread.sleep(5);

        assertThat(service.isBlocked("203.0.113.66")).isFalse();
    }

    @Test
    void requiredRedisStateRejectsMissingRedisTemplate() {
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> new ApiRateLimitService(null, true, true, Duration.ofHours(24)))
                .withMessage(ApiRateLimitService.STATE_UNAVAILABLE_MESSAGE);
    }

    @Test
    void requiredRedisStateFailsClosedWhenRedisCounterFails() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mockRedisValues(redisTemplate);
        when(values.increment(org.mockito.ArgumentMatchers.startsWith("api:rate:")))
                .thenThrow(new RuntimeException("redis unavailable"));
        ApiRateLimitService service = new ApiRateLimitService(redisTemplate, true, true, Duration.ofHours(24));

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> service.consume(RateLimitPolicy.SEARCH, "203.0.113.10", "anonymous"))
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
    }

    @Test
    void redisCounterSetsTtlOnFirstAllowedHit() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mockRedisValues(redisTemplate);
        when(values.increment(org.mockito.ArgumentMatchers.startsWith("api:rate:")))
                .thenReturn(1L, 1L, 1L);
        ApiRateLimitService service = new ApiRateLimitService(redisTemplate, true, true, Duration.ofHours(24));

        RateLimitDecision decision = service.consume(RateLimitPolicy.LOGIN, "203.0.113.10", "alice");

        assertThat(decision.allowed()).isTrue();
        verify(redisTemplate, times(3))
                .expire(org.mockito.ArgumentMatchers.startsWith("api:rate:"), eq(Duration.ofMinutes(1)));
    }

    @Test
    void redisCounterRejectsWithRedisTtl() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mockRedisValues(redisTemplate);
        when(values.increment(org.mockito.ArgumentMatchers.startsWith("api:rate:")))
                .thenReturn(RateLimitPolicy.LOGIN.capacity() + 1);
        when(redisTemplate.getExpire(org.mockito.ArgumentMatchers.startsWith("api:rate:")))
                .thenReturn(7L);
        ApiRateLimitService service = new ApiRateLimitService(redisTemplate, true, true, Duration.ofHours(24));

        RateLimitDecision decision = service.consume(RateLimitPolicy.LOGIN, "203.0.113.10", "alice");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.policy()).isEqualTo(RateLimitPolicy.LOGIN);
        assertThat(decision.retryAfterSeconds()).isEqualTo(7);
    }

    @Test
    void requiredRedisStateFailsClosedWhenRetryAfterLookupFails() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mockRedisValues(redisTemplate);
        when(values.increment(org.mockito.ArgumentMatchers.startsWith("api:rate:")))
                .thenReturn(RateLimitPolicy.LOGIN.capacity() + 1);
        when(redisTemplate.getExpire(org.mockito.ArgumentMatchers.startsWith("api:rate:")))
                .thenThrow(new RuntimeException("redis unavailable"));
        ApiRateLimitService service = new ApiRateLimitService(redisTemplate, true, true, Duration.ofHours(24));

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> service.consume(RateLimitPolicy.LOGIN, "203.0.113.10", "alice"))
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
    }

    @Test
    void redisBlockLookupIsUsedWhenRedisStateIsRequired() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.hasKey(org.mockito.ArgumentMatchers.startsWith("api:waf:block:")))
                .thenReturn(true);
        ApiRateLimitService service = new ApiRateLimitService(redisTemplate, true, true, Duration.ofHours(24));

        assertThat(service.isBlocked("203.0.113.66")).isTrue();
    }

    @Test
    void optionalRedisBlockMissFallsBackToNotBlocked() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.hasKey(org.mockito.ArgumentMatchers.startsWith("api:waf:block:")))
                .thenReturn(false);
        ApiRateLimitService service = new ApiRateLimitService(redisTemplate, true, false, Duration.ofHours(24));

        assertThat(service.isBlocked("203.0.113.66")).isFalse();
    }

    @Test
    void requiredRedisStateFailsClosedWhenBlockLookupFails() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.hasKey(org.mockito.ArgumentMatchers.startsWith("api:waf:block:")))
                .thenThrow(new RuntimeException("redis unavailable"));
        ApiRateLimitService service = new ApiRateLimitService(redisTemplate, true, true, Duration.ofHours(24));

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> service.isBlocked("203.0.113.66"))
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
    }

    @Test
    void blockForHoneypotWritesRedisWhenAvailable() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mockRedisValues(redisTemplate);
        ApiRateLimitService service = new ApiRateLimitService(redisTemplate, true, false, Duration.ofHours(24));

        service.blockForHoneypot("203.0.113.66");

        verify(values)
                .set(org.mockito.ArgumentMatchers.startsWith("api:waf:block:"), eq("1"), eq(Duration.ofHours(24)));
    }

    @Test
    void requiredRedisStateFailsClosedWhenBlockWriteFails() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mockRedisValues(redisTemplate);
        org.mockito.Mockito.doThrow(new RuntimeException("redis unavailable"))
                .when(values)
                .set(anyString(), eq("1"), any(Duration.class));
        ApiRateLimitService service = new ApiRateLimitService(redisTemplate, true, true, Duration.ofHours(24));

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> service.blockForHoneypot("203.0.113.66"))
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
    }

    @SuppressWarnings("unchecked")
    private static ValueOperations<String, String> mockRedisValues(StringRedisTemplate redisTemplate) {
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        return values;
    }
}
