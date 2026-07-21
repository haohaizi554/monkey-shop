package com.example.monkey.tenant.interfaces;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.monkey.shared.application.observability.AuditService;
import com.example.monkey.shared.application.security.ApiRateLimitApplicationService;
import com.example.monkey.shared.application.security.ApiRateLimitResult;
import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.application.tenant.PermissiveTenantAccessTestConfiguration;
import com.example.monkey.shared.infrastructure.config.SecurityConfig;
import com.example.monkey.shared.interfaces.web.VisitInterceptor;
import com.example.monkey.tenant.application.TenantApplicationService;
import com.example.monkey.tenant.domain.TenantExportProvider;
import com.example.monkey.user.domain.UserAccountStore;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(controllers = TenantAdminController.class)
@Import({SecurityConfig.class, PermissiveTenantAccessTestConfiguration.class})
@MockitoBean(
        types = {
            TenantApplicationService.class,
            UserAccountStore.class,
            VisitInterceptor.class,
            AuditService.class,
            ApiRateLimitApplicationService.class,
            com.example.monkey.shared.domain.security.TrustedProxyPolicy.class
        })
class TenantAdminControllerSecurityTest {

    private final MockMvc mockMvc;
    private final TenantApplicationService tenantApplicationService;
    private final ApiRateLimitApplicationService apiRateLimitService;

    @Autowired
    TenantAdminControllerSecurityTest(
            MockMvc mockMvc,
            TenantApplicationService tenantApplicationService,
            ApiRateLimitApplicationService apiRateLimitService) {
        this.mockMvc = mockMvc;
        this.tenantApplicationService = tenantApplicationService;
        this.apiRateLimitService = apiRateLimitService;
    }

    @BeforeEach
    void allowRateLimitedTraffic() {
        when(apiRateLimitService.consume(any(), any(), any())).thenReturn(new ApiRateLimitResult(true, 0));
    }

    @Test
    void tenantAdminCanDownloadCompletedArtifact() throws Exception {
        byte[] archive = "encrypted-tenant-export".getBytes(StandardCharsets.UTF_8);
        when(tenantApplicationService.downloadExportArtifact(200L, 2200L))
                .thenReturn(new TenantExportProvider.ExportArtifact(new ByteArrayInputStream(archive), archive.length));

        MvcResult streamingResponse = mockMvc.perform(
                        get("/api/v1/tenants/200/exports/2200/artifact").with(adminWith("TENANT_ADMIN")))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(streamingResponse))
                .andExpect(status().isOk())
                .andExpect(content().bytes(archive));
        verify(tenantApplicationService).downloadExportArtifact(200L, 2200L);
    }

    @Test
    void tenantReadOnlyAdminCannotDownloadArtifact() throws Exception {
        mockMvc.perform(get("/api/v1/tenants/200/exports/2200/artifact").with(adminWith("TENANT_READ")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(tenantApplicationService);
    }

    @Test
    void regularUserCannotDownloadTenantArtifact() throws Exception {
        mockMvc.perform(get("/api/v1/tenants/200/exports/2200/artifact").with(regularUser()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(tenantApplicationService);
    }

    private static RequestPostProcessor adminWith(String authority) {
        return authentication(new UsernamePasswordAuthenticationToken(
                new SessionUser(1L, "ADMIN", false, 1L),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority(authority))));
    }

    private static RequestPostProcessor regularUser() {
        return authentication(new UsernamePasswordAuthenticationToken(
                new SessionUser(7L, "USER", false, 200L),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("TENANT_ADMIN"))));
    }
}
