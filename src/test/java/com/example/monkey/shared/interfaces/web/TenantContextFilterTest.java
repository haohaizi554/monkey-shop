package com.example.monkey.shared.interfaces.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.application.tenant.TenantAccessGateway;
import com.example.monkey.shared.application.tenant.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class TenantContextFilterTest {

    private final TenantAccessGateway tenantAccessGateway = mock(TenantAccessGateway.class);
    private final TenantContextFilter filter =
            new TenantContextFilter(tenantAccessGateway, new ObjectMapper().findAndRegisterModules());

    @AfterEach
    void clear() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void adminHeaderSelectsTenantAndClearsContextAfterRequest() throws Exception {
        when(tenantAccessGateway.isServiceableTenant(200L)).thenReturn(true);
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(
                        new SessionUser(1L, "ADMIN", false, 1L),
                        null,
                        java.util.List.of(new SimpleGrantedAuthority("TENANT_READ"))));
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
    void ownServiceableTenantIsAcceptedWithoutAnExplicitHeader() throws Exception {
        when(tenantAccessGateway.isServiceableTenant(300L)).thenReturn(true);
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(
                        new SessionUser(7L, "USER", false, 300L), null, java.util.List.of()));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/orders/my");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainInvoked = new AtomicBoolean();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            chainInvoked.set(true);
            assertThat(TenantContext.currentTenantIdOrDefault()).isEqualTo(300L);
        });

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chainInvoked).isTrue();
        assertThat(TenantContext.currentTenantId()).isEmpty();
    }

    @Test
    void unserviceableTenantIsRejectedWithoutRevealingWhetherItExists() throws Exception {
        when(tenantAccessGateway.isServiceableTenant(300L)).thenReturn(false);
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(
                        new SessionUser(7L, "USER", false, 300L), null, java.util.List.of()));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/orders/my");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainInvoked = new AtomicBoolean();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> chainInvoked.set(true));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("\"code\":\"FORBIDDEN\"");
        assertThat(chainInvoked).isFalse();
        assertThat(TenantContext.currentTenantId()).isEmpty();
    }

    @Test
    void nonAdminCrossTenantHeaderIsRejectedAndContextIsCleared() throws Exception {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(
                        new SessionUser(7L, "USER", false, 300L), null, java.util.List.of()));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/orders/my");
        request.addHeader(TenantContextFilter.TENANT_HEADER, "200");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainInvoked = new AtomicBoolean();

        FilterChain chain = (servletRequest, servletResponse) -> chainInvoked.set(true);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(chainInvoked).isFalse();
        assertThat(TenantContext.currentTenantId()).isEmpty();
    }

    @Test
    void adminRoleWithoutTenantAuthorityCannotSelectAnotherTenant() throws Exception {
        when(tenantAccessGateway.isServiceableTenant(200L)).thenReturn(true);
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(
                        new SessionUser(1L, "ADMIN", false, 1L), null, java.util.List.of()));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/tenants/dashboard");
        request.addHeader(TenantContextFilter.TENANT_HEADER, "200");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainInvoked = new AtomicBoolean();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> chainInvoked.set(true));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(chainInvoked).isFalse();
        assertThat(TenantContext.currentTenantId()).isEmpty();
    }

    @Test
    void malformedExplicitTenantHeaderIsRejectedInsteadOfFallingBack() throws Exception {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(
                        new SessionUser(1L, "ADMIN", false, 1L), null, java.util.List.of()));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/tenants/dashboard");
        request.addHeader(TenantContextFilter.TENANT_HEADER, "1junk");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainInvoked = new AtomicBoolean();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> chainInvoked.set(true));

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(chainInvoked).isFalse();
        assertThat(TenantContext.currentTenantId()).isEmpty();
    }

    @Test
    void nonApiResourcesBypassTenantLookup() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/assets/application.js");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainInvoked = new AtomicBoolean();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> chainInvoked.set(true));

        assertThat(chainInvoked).isTrue();
        verifyNoInteractions(tenantAccessGateway);
        assertThat(TenantContext.currentTenantId()).isEmpty();
    }
}
