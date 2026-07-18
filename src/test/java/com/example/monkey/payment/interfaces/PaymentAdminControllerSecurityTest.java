package com.example.monkey.payment.interfaces;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.monkey.payment.application.PaymentApplicationService;
import com.example.monkey.payment.application.dto.PaymentRefundRequestDto;
import com.example.monkey.shared.application.observability.AuditService;
import com.example.monkey.shared.application.security.ApiRateLimitApplicationService;
import com.example.monkey.shared.application.security.ApiRateLimitResult;
import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.application.tenant.PermissiveTenantAccessTestConfiguration;
import com.example.monkey.shared.infrastructure.config.SecurityConfig;
import com.example.monkey.shared.interfaces.web.VisitInterceptor;
import com.example.monkey.user.domain.UserAccountStore;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(controllers = PaymentAdminController.class)
@Import({SecurityConfig.class, PermissiveTenantAccessTestConfiguration.class})
@MockitoBean(
        types = {
            PaymentApplicationService.class,
            UserAccountStore.class,
            VisitInterceptor.class,
            AuditService.class,
            ApiRateLimitApplicationService.class,
            com.example.monkey.shared.domain.security.TrustedProxyPolicy.class
        })
class PaymentAdminControllerSecurityTest {

    private final MockMvc mockMvc;
    private final PaymentApplicationService paymentApplicationService;
    private final ApiRateLimitApplicationService apiRateLimitService;

    @Autowired
    PaymentAdminControllerSecurityTest(
            MockMvc mockMvc,
            PaymentApplicationService paymentApplicationService,
            ApiRateLimitApplicationService apiRateLimitService) {
        this.mockMvc = mockMvc;
        this.paymentApplicationService = paymentApplicationService;
        this.apiRateLimitService = apiRateLimitService;
    }

    @BeforeEach
    void allowRateLimitedTraffic() {
        org.mockito.Mockito.when(apiRateLimitService.consume(any(), any(), any()))
                .thenReturn(new ApiRateLimitResult(true, 0));
    }

    @Test
    void adminCanReadAnotherUsersPaymentThroughAdminRoute() throws Exception {
        mockMvc.perform(get("/api/payments/admin/orders/10").with(authenticatedUser(1L, "ADMIN")))
                .andExpect(status().isOk());

        verify(paymentApplicationService).findByOrderAsAdmin(any(SessionUser.class), eq(10L), eq("127.0.0.1"));
    }

    @Test
    void regularUserCannotReadAdminPaymentRoute() throws Exception {
        mockMvc.perform(get("/api/payments/admin/orders/10").with(authenticatedUser(42L, "USER")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(paymentApplicationService);
    }

    @Test
    void adminCanRefundAnotherUsersPaymentThroughAdminRoute() throws Exception {
        mockMvc.perform(post("/api/payments/admin/refund")
                        .with(csrf())
                        .with(authenticatedUser(1L, "ADMIN"))
                        .header("Idempotency-Key", "admin-refund-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentNo\":\"PAY100\",\"amount\":30.00,\"reason\":\"approved return\"}"))
                .andExpect(status().isOk());

        verify(paymentApplicationService)
                .refundAsAdmin(
                        any(SessionUser.class),
                        eq(new PaymentRefundRequestDto("PAY100", new java.math.BigDecimal("30.00"), "approved return")),
                        eq("admin-refund-key"),
                        eq("127.0.0.1"));
    }

    @Test
    void regularUserCannotRefundThroughAdminRoute() throws Exception {
        mockMvc.perform(post("/api/payments/admin/refund")
                        .with(csrf())
                        .with(authenticatedUser(42L, "USER"))
                        .header("Idempotency-Key", "admin-refund-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentNo\":\"PAY100\",\"amount\":30.00}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(paymentApplicationService);
    }

    private static RequestPostProcessor authenticatedUser(Long id, String role) {
        UsernamePasswordAuthenticationToken token =
                new UsernamePasswordAuthenticationToken(new SessionUser(id, role), null, authorities(role));
        return authentication(token);
    }

    private static List<SimpleGrantedAuthority> authorities(String role) {
        if ("ADMIN".equals(role)) {
            return List.of(new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("ORDER_MANAGE"));
        }
        return List.of(new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ORDER_READ_OWN"));
    }
}
