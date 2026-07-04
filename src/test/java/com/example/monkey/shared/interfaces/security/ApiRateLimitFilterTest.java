package com.example.monkey.shared.interfaces.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.shared.application.security.ApiRateLimitApplicationService;
import com.example.monkey.shared.application.security.ApiRateLimitOperation;
import com.example.monkey.shared.application.security.ApiRateLimitResult;
import com.example.monkey.shared.application.security.SessionUser;
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

    private final ApiRateLimitApplicationService rateLimitService = mock(ApiRateLimitApplicationService.class);
    private final ApiRateLimitFilter filter = new ApiRateLimitFilter(rateLimitService, new ObjectMapper());

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void mapsOrderCreateToUserScopedOrderPolicy() throws Exception {
        when(rateLimitService.consume(ApiRateLimitOperation.ORDER, "203.0.113.10", "user:42"))
                .thenReturn(new ApiRateLimitResult(true, 0));
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(
                        new SessionUser(42L, "USER"), null, java.util.List.of()));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/orders/create");
        request.addHeader("X-Forwarded-For", "203.0.113.10");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(rateLimitService).consume(ApiRateLimitOperation.ORDER, "203.0.113.10", "user:42");
    }

    @Test
    void versionedApiPathsUseSameRateLimitPoliciesAsLegacyPaths() throws Exception {
        when(rateLimitService.consume(ApiRateLimitOperation.LOGIN, "127.0.0.1", "anonymous"))
                .thenReturn(new ApiRateLimitResult(true, 0));
        MockHttpServletRequest loginRequest = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        loginRequest.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse loginResponse = new MockHttpServletResponse();
        FilterChain loginChain = mock(FilterChain.class);

        filter.doFilter(loginRequest, loginResponse, loginChain);

        verify(loginChain).doFilter(loginRequest, loginResponse);
        verify(rateLimitService).consume(ApiRateLimitOperation.LOGIN, "127.0.0.1", "anonymous");

        when(rateLimitService.consume(ApiRateLimitOperation.ORDER, "127.0.0.1", "anonymous"))
                .thenReturn(new ApiRateLimitResult(true, 0));
        MockHttpServletRequest orderRequest = new MockHttpServletRequest("POST", "/api/v1/orders/create");
        orderRequest.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse orderResponse = new MockHttpServletResponse();
        FilterChain orderChain = mock(FilterChain.class);

        filter.doFilter(orderRequest, orderResponse, orderChain);

        verify(orderChain).doFilter(orderRequest, orderResponse);
        verify(rateLimitService).consume(ApiRateLimitOperation.ORDER, "127.0.0.1", "anonymous");

        when(rateLimitService.consume(ApiRateLimitOperation.SEARCH, "127.0.0.1", "anonymous"))
                .thenReturn(new ApiRateLimitResult(true, 0));
        MockHttpServletRequest searchRequest = new MockHttpServletRequest("GET", "/api/v1/monkeys");
        searchRequest.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse searchResponse = new MockHttpServletResponse();
        FilterChain searchChain = mock(FilterChain.class);

        filter.doFilter(searchRequest, searchResponse, searchChain);

        verify(searchChain).doFilter(searchRequest, searchResponse);
        verify(rateLimitService).consume(ApiRateLimitOperation.SEARCH, "127.0.0.1", "anonymous");

        when(rateLimitService.consume(ApiRateLimitOperation.RISK, "127.0.0.1", "anonymous"))
                .thenReturn(new ApiRateLimitResult(true, 0));
        MockHttpServletRequest riskRequest = new MockHttpServletRequest("POST", "/api/v1/risk/assess");
        riskRequest.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse riskResponse = new MockHttpServletResponse();
        FilterChain riskChain = mock(FilterChain.class);

        filter.doFilter(riskRequest, riskResponse, riskChain);

        verify(riskChain).doFilter(riskRequest, riskResponse);
        verify(rateLimitService).consume(ApiRateLimitOperation.RISK, "127.0.0.1", "anonymous");

        when(rateLimitService.consume(ApiRateLimitOperation.UPLOAD, "127.0.0.1", "anonymous"))
                .thenReturn(new ApiRateLimitResult(true, 0));
        MockHttpServletRequest uploadRequest = new MockHttpServletRequest("POST", "/api/v1/uploads/product");
        uploadRequest.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse uploadResponse = new MockHttpServletResponse();
        FilterChain uploadChain = mock(FilterChain.class);

        filter.doFilter(uploadRequest, uploadResponse, uploadChain);

        verify(uploadChain).doFilter(uploadRequest, uploadResponse);
        verify(rateLimitService).consume(ApiRateLimitOperation.UPLOAD, "127.0.0.1", "anonymous");
    }

    @Test
    void cartMutationsUseCartPolicyAcrossUnsafeMethodsAndVersionedPaths() throws Exception {
        when(rateLimitService.consume(ApiRateLimitOperation.CART, "127.0.0.1", "anonymous"))
                .thenReturn(new ApiRateLimitResult(true, 0));

        MockHttpServletRequest addRequest = new MockHttpServletRequest("POST", "/api/cart/items");
        addRequest.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse addResponse = new MockHttpServletResponse();
        FilterChain addChain = mock(FilterChain.class);
        filter.doFilter(addRequest, addResponse, addChain);

        MockHttpServletRequest updateRequest = new MockHttpServletRequest("PATCH", "/api/v1/cart/items/1001");
        updateRequest.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse updateResponse = new MockHttpServletResponse();
        FilterChain updateChain = mock(FilterChain.class);
        filter.doFilter(updateRequest, updateResponse, updateChain);

        MockHttpServletRequest deleteRequest = new MockHttpServletRequest("DELETE", "/api/cart/items/1001");
        deleteRequest.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse deleteResponse = new MockHttpServletResponse();
        FilterChain deleteChain = mock(FilterChain.class);
        filter.doFilter(deleteRequest, deleteResponse, deleteChain);

        verify(addChain).doFilter(addRequest, addResponse);
        verify(updateChain).doFilter(updateRequest, updateResponse);
        verify(deleteChain).doFilter(deleteRequest, deleteResponse);
        verify(rateLimitService, times(3)).consume(ApiRateLimitOperation.CART, "127.0.0.1", "anonymous");
    }

    @Test
    void cartReadsAndCartLikePrefixesDoNotUseCartMutationPolicy() throws Exception {
        when(rateLimitService.consume(ApiRateLimitOperation.DEFAULT, "127.0.0.1", "anonymous"))
                .thenReturn(new ApiRateLimitResult(true, 0));

        MockHttpServletRequest readRequest = new MockHttpServletRequest("GET", "/api/cart");
        readRequest.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse readResponse = new MockHttpServletResponse();
        FilterChain readChain = mock(FilterChain.class);
        filter.doFilter(readRequest, readResponse, readChain);

        MockHttpServletRequest prefixRequest = new MockHttpServletRequest("POST", "/api/cartography");
        prefixRequest.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse prefixResponse = new MockHttpServletResponse();
        FilterChain prefixChain = mock(FilterChain.class);
        filter.doFilter(prefixRequest, prefixResponse, prefixChain);

        verify(readChain).doFilter(readRequest, readResponse);
        verify(prefixChain).doFilter(prefixRequest, prefixResponse);
        verify(rateLimitService, times(2)).consume(ApiRateLimitOperation.DEFAULT, "127.0.0.1", "anonymous");
    }

    @Test
    void logisticsPaymentSeckillAndMembershipPathsUseDedicatedPolicies() throws Exception {
        when(rateLimitService.consume(ApiRateLimitOperation.LOGISTICS, "127.0.0.1", "anonymous"))
                .thenReturn(new ApiRateLimitResult(true, 0));
        MockHttpServletRequest logisticsRequest =
                new MockHttpServletRequest("GET", "/api/v1/logistics/tracking/SF7000");
        logisticsRequest.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse logisticsResponse = new MockHttpServletResponse();
        FilterChain logisticsChain = mock(FilterChain.class);

        filter.doFilter(logisticsRequest, logisticsResponse, logisticsChain);

        verify(logisticsChain).doFilter(logisticsRequest, logisticsResponse);
        verify(rateLimitService).consume(ApiRateLimitOperation.LOGISTICS, "127.0.0.1", "anonymous");

        when(rateLimitService.consume(ApiRateLimitOperation.PAYMENT, "127.0.0.1", "anonymous"))
                .thenReturn(new ApiRateLimitResult(true, 0));
        MockHttpServletRequest paymentRequest = new MockHttpServletRequest("POST", "/api/v1/payments/refund");
        paymentRequest.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse paymentResponse = new MockHttpServletResponse();
        FilterChain paymentChain = mock(FilterChain.class);

        filter.doFilter(paymentRequest, paymentResponse, paymentChain);

        verify(paymentChain).doFilter(paymentRequest, paymentResponse);
        verify(rateLimitService).consume(ApiRateLimitOperation.PAYMENT, "127.0.0.1", "anonymous");

        when(rateLimitService.consume(ApiRateLimitOperation.SECKILL, "127.0.0.1", "anonymous"))
                .thenReturn(new ApiRateLimitResult(true, 0));
        MockHttpServletRequest seckillRequest = new MockHttpServletRequest("POST", "/api/marketing/seckill-orders");
        seckillRequest.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse seckillResponse = new MockHttpServletResponse();
        FilterChain seckillChain = mock(FilterChain.class);

        filter.doFilter(seckillRequest, seckillResponse, seckillChain);

        verify(seckillChain).doFilter(seckillRequest, seckillResponse);
        verify(rateLimitService).consume(ApiRateLimitOperation.SECKILL, "127.0.0.1", "anonymous");

        when(rateLimitService.consume(ApiRateLimitOperation.MEMBERSHIP, "127.0.0.1", "anonymous"))
                .thenReturn(new ApiRateLimitResult(true, 0));
        MockHttpServletRequest membershipRequest = new MockHttpServletRequest("GET", "/api/v1/membership/dashboard");
        membershipRequest.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse membershipResponse = new MockHttpServletResponse();
        FilterChain membershipChain = mock(FilterChain.class);

        filter.doFilter(membershipRequest, membershipResponse, membershipChain);

        verify(membershipChain).doFilter(membershipRequest, membershipResponse);
        verify(rateLimitService).consume(ApiRateLimitOperation.MEMBERSHIP, "127.0.0.1", "anonymous");

        when(rateLimitService.consume(ApiRateLimitOperation.SEARCH, "127.0.0.1", "anonymous"))
                .thenReturn(new ApiRateLimitResult(true, 0));
        MockHttpServletRequest searchRequest = new MockHttpServletRequest("GET", "/api/v1/search/products");
        searchRequest.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse searchResponse = new MockHttpServletResponse();
        FilterChain searchChain = mock(FilterChain.class);

        filter.doFilter(searchRequest, searchResponse, searchChain);

        verify(searchChain).doFilter(searchRequest, searchResponse);
        verify(rateLimitService).consume(ApiRateLimitOperation.SEARCH, "127.0.0.1", "anonymous");
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
        when(rateLimitService.consume(ApiRateLimitOperation.LOGIN, "127.0.0.1", "principal:alice"))
                .thenReturn(new ApiRateLimitResult(true, 0));
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken("Alice", null, java.util.List.of()));
        MockHttpServletRequest loginRequest = new MockHttpServletRequest("POST", "/api/auth/login");
        loginRequest.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse loginResponse = new MockHttpServletResponse();
        FilterChain loginChain = mock(FilterChain.class);

        filter.doFilter(loginRequest, loginResponse, loginChain);

        verify(loginChain).doFilter(loginRequest, loginResponse);
        verify(rateLimitService).consume(ApiRateLimitOperation.LOGIN, "127.0.0.1", "principal:alice");

        when(rateLimitService.consume(ApiRateLimitOperation.REGISTER, "127.0.0.1", "anonymous"))
                .thenReturn(new ApiRateLimitResult(true, 0));
        SecurityContextHolder.clearContext();
        MockHttpServletRequest registerRequest = new MockHttpServletRequest("POST", "/api/auth/register");
        registerRequest.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse registerResponse = new MockHttpServletResponse();
        FilterChain registerChain = mock(FilterChain.class);

        filter.doFilter(registerRequest, registerResponse, registerChain);

        verify(registerChain).doFilter(registerRequest, registerResponse);
        verify(rateLimitService).consume(ApiRateLimitOperation.REGISTER, "127.0.0.1", "anonymous");

        when(rateLimitService.consume(ApiRateLimitOperation.UPLOAD, "127.0.0.1", "anonymous"))
                .thenReturn(new ApiRateLimitResult(true, 0));
        MockHttpServletRequest uploadRequest = new MockHttpServletRequest("POST", "/api/upload/avatar");
        uploadRequest.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse uploadResponse = new MockHttpServletResponse();
        FilterChain uploadChain = mock(FilterChain.class);

        filter.doFilter(uploadRequest, uploadResponse, uploadChain);

        verify(uploadChain).doFilter(uploadRequest, uploadResponse);
        verify(rateLimitService).consume(ApiRateLimitOperation.UPLOAD, "127.0.0.1", "anonymous");
    }

    @Test
    void defaultPolicyAppliesToOtherApiRequests() throws Exception {
        when(rateLimitService.consume(ApiRateLimitOperation.DEFAULT, "127.0.0.1", "anonymous"))
                .thenReturn(new ApiRateLimitResult(true, 0));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/profile");
        request.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(rateLimitService).consume(ApiRateLimitOperation.DEFAULT, "127.0.0.1", "anonymous");
    }

    @Test
    void rejectedRequestReturnsTooManyRequestsAndRetryAfter() throws Exception {
        when(rateLimitService.consume(ApiRateLimitOperation.SEARCH, "127.0.0.1", "anonymous"))
                .thenReturn(new ApiRateLimitResult(false, 12));
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

        MockHttpServletRequest searchTrap = new MockHttpServletRequest("GET", "/api/v1/search/internal/hot");
        searchTrap.setRemoteAddr("198.51.100.25");
        MockHttpServletResponse trapResponse = new MockHttpServletResponse();
        FilterChain trapChain = mock(FilterChain.class);

        filter.doFilter(searchTrap, trapResponse, trapChain);

        assertThat(trapResponse.getStatus()).isEqualTo(403);
        verify(rateLimitService).blockForHoneypot("198.51.100.25");
        verify(trapChain, never()).doFilter(searchTrap, trapResponse);

        MockHttpServletRequest riskTrap = new MockHttpServletRequest("GET", "/api/v1/risk/internal/probe");
        riskTrap.setRemoteAddr("198.51.100.26");
        MockHttpServletResponse riskTrapResponse = new MockHttpServletResponse();
        FilterChain riskTrapChain = mock(FilterChain.class);

        filter.doFilter(riskTrap, riskTrapResponse, riskTrapChain);

        assertThat(riskTrapResponse.getStatus()).isEqualTo(403);
        verify(rateLimitService).blockForHoneypot("198.51.100.26");
        verify(riskTrapChain, never()).doFilter(riskTrap, riskTrapResponse);
    }
}
