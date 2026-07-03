package com.example.monkey.shared.application.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.shared.domain.security.ApiRateLimiter;
import com.example.monkey.shared.domain.security.ApiRateLimiter.RateLimitDecision;
import com.example.monkey.shared.domain.security.RateLimitPolicy;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ApiRateLimitApplicationServiceTest {

    private final ApiRateLimiter rateLimiter = mock(ApiRateLimiter.class);
    private final ApiRateLimitApplicationService service = new ApiRateLimitApplicationService(rateLimiter);

    @ParameterizedTest
    @MethodSource("operationMappings")
    void mapsApplicationOperationToDomainPolicy(ApiRateLimitOperation operation, RateLimitPolicy policy) {
        when(rateLimiter.consume(policy, "203.0.113.10", "user:42")).thenReturn(RateLimitDecision.rejected(policy, 12));

        ApiRateLimitResult result = service.consume(operation, "203.0.113.10", "user:42");

        assertThat(result.allowed()).isFalse();
        assertThat(result.retryAfterSeconds()).isEqualTo(12);
        verify(rateLimiter).consume(policy, "203.0.113.10", "user:42");
    }

    @Test
    void nullOperationUsesDefaultPolicy() {
        when(rateLimiter.consume(RateLimitPolicy.DEFAULT, "127.0.0.1", "anonymous"))
                .thenReturn(RateLimitDecision.allowedDecision());

        ApiRateLimitResult result = service.consume(null, "127.0.0.1", "anonymous");

        assertThat(result.allowed()).isTrue();
        assertThat(result.retryAfterSeconds()).isZero();
        verify(rateLimiter).consume(RateLimitPolicy.DEFAULT, "127.0.0.1", "anonymous");
    }

    @Test
    void delegatesHoneypotBlockState() {
        when(rateLimiter.isBlocked("198.51.100.24")).thenReturn(true);

        assertThat(service.isBlocked("198.51.100.24")).isTrue();
        service.blockForHoneypot("198.51.100.24");

        verify(rateLimiter).isBlocked("198.51.100.24");
        verify(rateLimiter).blockForHoneypot("198.51.100.24");
    }

    private static Stream<Arguments> operationMappings() {
        return Stream.of(
                Arguments.of(ApiRateLimitOperation.LOGIN, RateLimitPolicy.LOGIN),
                Arguments.of(ApiRateLimitOperation.REGISTER, RateLimitPolicy.REGISTER),
                Arguments.of(ApiRateLimitOperation.ORDER, RateLimitPolicy.ORDER),
                Arguments.of(ApiRateLimitOperation.CART, RateLimitPolicy.CART),
                Arguments.of(ApiRateLimitOperation.SEARCH, RateLimitPolicy.SEARCH),
                Arguments.of(ApiRateLimitOperation.UPLOAD, RateLimitPolicy.UPLOAD),
                Arguments.of(ApiRateLimitOperation.DEFAULT, RateLimitPolicy.DEFAULT));
    }
}
