package com.example.monkey.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.domain.security.ApiRateLimiter;
import com.example.monkey.domain.security.ApiRateLimiter.RateLimitDecision;
import com.example.monkey.domain.security.RateLimitPolicy;
import com.example.monkey.domain.user.SessionUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class ApiRateLimitFilterTest {

    private final ApiRateLimiter rateLimitService = mock(ApiRateLimiter.class);
    private final ApiRateLimitFilter filter = new ApiRateLimitFilter(rateLimitService, new ObjectMapper());

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void mapsOrderCreateToUserScopedOrderPolicy() throws Exception {
        when(rateLimitService.consume(RateLimitPolicy.ORDER, "203.0.113.10", "user:42"))
                .thenReturn(new RateLimitDecision(true, null, 0));
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(
                        new SessionUser(42L, "USER"), null, java.util.List.of()));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/orders/create");
        request.addHeader("X-Forwarded-For", "203.0.113.10");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(rateLimitService).consume(RateLimitPolicy.ORDER, "203.0.113.10", "user:42");
    }

    @Test
    void versionedApiPathsUseSameRateLimitPoliciesAsLegacyPaths() throws Exception {
        when(rateLimitService.consume(RateLimitPolicy.LOGIN, "127.0.0.1", "anonymous"))
                .thenReturn(new RateLimitDecision(true, null, 0));
        MockHttpServletRequest loginRequest = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        loginRequest.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse loginResponse = new MockHttpServletResponse();
        FilterChain loginChain = mock(FilterChain.class);

        filter.doFilter(loginRequest, loginResponse, loginChain);

        verify(loginChain).doFilter(loginRequest, loginResponse);
        verify(rateLimitService).consume(RateLimitPolicy.LOGIN, "127.0.0.1", "anonymous");

        when(rateLimitService.consume(RateLimitPolicy.ORDER, "127.0.0.1", "anonymous"))
                .thenReturn(new RateLimitDecision(true, null, 0));
        MockHttpServletRequest orderRequest = new MockHttpServletRequest("POST", "/api/v1/orders/create");
        orderRequest.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse orderResponse = new MockHttpServletResponse();
        FilterChain orderChain = mock(FilterChain.class);

        filter.doFilter(orderRequest, orderResponse, orderChain);

        verify(orderChain).doFilter(orderRequest, orderResponse);
        verify(rateLimitService).consume(RateLimitPolicy.ORDER, "127.0.0.1", "anonymous");

        when(rateLimitService.consume(RateLimitPolicy.SEARCH, "127.0.0.1", "anonymous"))
                .thenReturn(new RateLimitDecision(true, null, 0));
        MockHttpServletRequest searchRequest = new MockHttpServletRequest("GET", "/api/v1/monkeys");
        searchRequest.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse searchResponse = new MockHttpServletResponse();
        FilterChain searchChain = mock(FilterChain.class);

        filter.doFilter(searchRequest, searchResponse, searchChain);

        verify(searchChain).doFilter(searchRequest, searchResponse);
        verify(rateLimitService).consume(RateLimitPolicy.SEARCH, "127.0.0.1", "anonymous");

        when(rateLimitService.consume(RateLimitPolicy.UPLOAD, "127.0.0.1", "anonymous"))
                .thenReturn(new RateLimitDecision(true, null, 0));
        MockHttpServletRequest uploadRequest = new MockHttpServletRequest("POST", "/api/v1/uploads/product");
        uploadRequest.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse uploadResponse = new MockHttpServletResponse();
        FilterChain uploadChain = mock(FilterChain.class);

        filter.doFilter(uploadRequest, uploadResponse, uploadChain);

        verify(uploadChain).doFilter(uploadRequest, uploadResponse);
        verify(rateLimitService).consume(RateLimitPolicy.UPLOAD, "127.0.0.1", "anonymous");
    }

    @Test
    void skipsNonApiAndOptionsRequests() throws Exception {
        MockHttpServletResponse pageResponse = new MockHttpServletResponse();
        MockHttpServletRequest pageRequest = new MockHttpServletRequest("GET", "/");
        FilterChain pageChain = mock(FilterChain.class);

        filter.doFilter(pageRequest, pageResponse, pageChain);

        verify(pageChain).doFilter(pageRequest, pageResponse);

        MockHttpServletResponse optionsResponse = new MockHttpServletResponse();
        MockHttpServletRequest optionsRequest = new MockHttpServletRequest("OPTIONS", "/api/orders/create");
        FilterChain optionsChain = mock(FilterChain.class);

        filter.doFilter(optionsRequest, optionsResponse, optionsChain);

        verify(optionsChain).doFilter(optionsRequest, optionsResponse);
        verify(rateLimitService, never())
                .consume(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
    }

    @Test
    void blockedClientIpReceivesForbiddenProblem() throws Exception {
        when(rateLimitService.isBlocked("198.51.100.24")).thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/monkeys");
        request.setRemoteAddr("198.51.100.24");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getCharacterEncoding()).isEqualTo("UTF-8");
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void mapsAuthAndUploadPoliciesAndPrincipalNameFallback() throws Exception {
        when(rateLimitService.consume(RateLimitPolicy.LOGIN, "127.0.0.1", "principal:alice"))
                .thenReturn(new RateLimitDecision(true, null, 0));
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken("Alice", null, java.util.List.of()));
        MockHttpServletRequest loginRequest = new MockHttpServletRequest("POST", "/api/auth/login");
        loginRequest.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse loginResponse = new MockHttpServletResponse();
        FilterChain loginChain = mock(FilterChain.class);

        filter.doFilter(loginRequest, loginResponse, loginChain);

        verify(loginChain).doFilter(loginRequest, loginResponse);
        verify(rateLimitService).consume(RateLimitPolicy.LOGIN, "127.0.0.1", "principal:alice");

        when(rateLimitService.consume(RateLimitPolicy.REGISTER, "127.0.0.1", "anonymous"))
                .thenReturn(new RateLimitDecision(true, null, 0));
        SecurityContextHolder.clearContext();
        MockHttpServletRequest registerRequest = new MockHttpServletRequest("POST", "/api/auth/register");
        registerRequest.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse registerResponse = new MockHttpServletResponse();
        FilterChain registerChain = mock(FilterChain.class);

        filter.doFilter(registerRequest, registerResponse, registerChain);

        verify(registerChain).doFilter(registerRequest, registerResponse);
        verify(rateLimitService).consume(RateLimitPolicy.REGISTER, "127.0.0.1", "anonymous");

        when(rateLimitService.consume(RateLimitPolicy.UPLOAD, "127.0.0.1", "anonymous"))
                .thenReturn(new RateLimitDecision(true, null, 0));
        MockHttpServletRequest uploadRequest = new MockHttpServletRequest("POST", "/api/upload/avatar");
        uploadRequest.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse uploadResponse = new MockHttpServletResponse();
        FilterChain uploadChain = mock(FilterChain.class);

        filter.doFilter(uploadRequest, uploadResponse, uploadChain);

        verify(uploadChain).doFilter(uploadRequest, uploadResponse);
        verify(rateLimitService).consume(RateLimitPolicy.UPLOAD, "127.0.0.1", "anonymous");
    }

    @Test
    void defaultPolicyAppliesToOtherApiRequests() throws Exception {
        when(rateLimitService.consume(RateLimitPolicy.DEFAULT, "127.0.0.1", "anonymous"))
                .thenReturn(new RateLimitDecision(true, null, 0));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/profile");
        request.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(rateLimitService).consume(RateLimitPolicy.DEFAULT, "127.0.0.1", "anonymous");
    }

    @Test
    void rejectedRequestReturnsTooManyRequestsAndRetryAfter() throws Exception {
        when(rateLimitService.consume(RateLimitPolicy.SEARCH, "127.0.0.1", "anonymous"))
                .thenReturn(new RateLimitDecision(false, RateLimitPolicy.SEARCH, 12));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/monkeys");
        request.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("12");
        assertThat(response.getContentAsString()).contains("\"code\":\"RATE_LIMIT\"");
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void honeypotRequestBlocksClientIp() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/.env");
        request.setRemoteAddr("198.51.100.24");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        verify(rateLimitService).blockForHoneypot("198.51.100.24");
        verify(chain, never()).doFilter(request, response);
    }
}
