package com.example.monkey.shared.interfaces.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.application.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class TenantContextFilterTest {

    private final TenantContextFilter filter = new TenantContextFilter();

    @AfterEach
    void clear() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void adminHeaderSelectsTenantAndClearsContextAfterRequest() throws Exception {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(
                        new SessionUser(1L, "ADMIN", false, 1L), null, java.util.List.of()));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/tenants/dashboard");
        request.addHeader(TenantContextFilter.TENANT_HEADER, "200");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain chain = (servletRequest, servletResponse) -> {
            assertThat(TenantContext.currentTenantIdOrDefault()).isEqualTo(200L);
            assertThat(servletRequest.getAttribute(TenantContextFilter.TENANT_ATTRIBUTE))
                    .isEqualTo(200L);
        };

        filter.doFilter(request, response, chain);

        assertThat(TenantContext.currentTenantId()).isEmpty();
    }

    @Test
    void nonAdminPrincipalTenantCannotBeOverriddenByHeader() throws Exception {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(
                        new SessionUser(7L, "USER", false, 300L), null, java.util.List.of()));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/orders/my");
        request.addHeader(TenantContextFilter.TENANT_HEADER, "200");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain chain = (servletRequest, servletResponse) ->
                assertThat(TenantContext.currentTenantIdOrDefault()).isEqualTo(300L);

        filter.doFilter(request, response, chain);
    }
}
