package com.example.monkey.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.monkey.shared.application.observability.AuditService;
import com.example.monkey.shared.application.security.ApiRateLimitApplicationService;
import com.example.monkey.shared.application.security.ApiRateLimitResult;
import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.application.tenant.PermissiveTenantAccessTestConfiguration;
import com.example.monkey.shared.domain.security.TrustedProxyPolicy;
import com.example.monkey.shared.infrastructure.config.SecurityConfig;
import com.example.monkey.shared.interfaces.security.ApiRateLimitFilter;
import com.example.monkey.shared.interfaces.web.VisitInterceptor;
import com.example.monkey.user.domain.UserAccountStore;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(controllers = SecurityFilterChainMatrixTest.MatrixController.class)
@Import({
    SecurityConfig.class,
    ApiRateLimitFilter.class,
    SecurityFilterChainMatrixTest.MatrixController.class,
    PermissiveTenantAccessTestConfiguration.class
})
@TestPropertySource(properties = "app.security.csp.upgrade-insecure-requests=false")
@MockitoBean(
        types = {
            VisitInterceptor.class,
            UserAccountStore.class,
            AuditService.class,
            ApiRateLimitApplicationService.class,
            TrustedProxyPolicy.class
        })
class SecurityFilterChainMatrixTest {

    private final MockMvc mockMvc;
    private final ApiRateLimitApplicationService apiRateLimitService;

    @Autowired
    SecurityFilterChainMatrixTest(MockMvc mockMvc, ApiRateLimitApplicationService apiRateLimitService) {
        this.mockMvc = mockMvc;
        this.apiRateLimitService = apiRateLimitService;
    }

    @BeforeEach
    void allowRateLimitedTraffic() {
        when(apiRateLimitService.consume(any(), any(), any())).thenReturn(new ApiRateLimitResult(true, 0));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("requests")
    void enforcesExpectedDecision(
            String description,
            HttpMethod method,
            String path,
            PrincipalKind principal,
            boolean includeCsrf,
            int expectedStatus)
            throws Exception {
        MockHttpServletRequestBuilder request = request(method, path).secure(true);
        if (principal != PrincipalKind.ANONYMOUS) {
            request.with(authentication(principal.authentication()));
        }
        if (includeCsrf) {
            request.with(csrf());
        }

        mockMvc.perform(request).andExpect(status().is(expectedStatus));
    }

    static Stream<Arguments> requests() {
        Stream<Arguments> catalogReads = aliases(
                List.of(
                        "/catalog/categories",
                        "/catalog/categories/tree",
                        "/catalog/spus",
                        "/catalog/spus/42",
                        "/catalog/skus/7"),
                HttpMethod.GET,
                PrincipalKind.ANONYMOUS,
                false,
                200,
                "public catalog read");
        Stream<Arguments> catalogWrites = Stream.concat(
                aliases(
                        List.of("/catalog/spus", "/catalog/spus/42/status"),
                        HttpMethod.POST,
                        PrincipalKind.USER,
                        true,
                        403,
                        "catalog write without PRODUCT_MANAGE"),
                aliases(
                        List.of("/catalog/spus", "/catalog/spus/42/status"),
                        HttpMethod.POST,
                        PrincipalKind.PRODUCT_MANAGER,
                        true,
                        200,
                        "catalog write with PRODUCT_MANAGE"));
        Stream<Arguments> marketingActions = aliases(
                List.of(
                        "/marketing/coupons/claim",
                        "/marketing/coupons/redeem",
                        "/marketing/coupons/return",
                        "/marketing/price/quote",
                        "/marketing/seckill-orders",
                        "/marketing/group-buy/join"),
                HttpMethod.POST,
                PrincipalKind.USER,
                true,
                200,
                "owner-facing marketing action");
        Stream<Arguments> marketingAdminReturn = aliases(
                List.of("/marketing/coupons/return"),
                HttpMethod.POST,
                PrincipalKind.ORDER_MANAGER,
                true,
                200,
                "coupon return with ORDER_MANAGE");
        Stream<Arguments> inventoryReleaseDenied = aliases(
                List.of("/inventory/reservations/r-1/release", "/inventory/compensations"),
                HttpMethod.POST,
                PrincipalKind.USER,
                true,
                403,
                "inventory compensation without ORDER_MANAGE");
        Stream<Arguments> inventoryReleaseAllowed = aliases(
                List.of("/inventory/reservations/r-1/release", "/inventory/compensations"),
                HttpMethod.POST,
                PrincipalKind.ORDER_MANAGER,
                true,
                200,
                "inventory compensation with ORDER_MANAGE");
        Stream<Arguments> membershipAdminActions = Stream.concat(
                aliases(
                        List.of("/membership/points/earn", "/membership/level", "/membership/price-drops/scan"),
                        HttpMethod.POST,
                        PrincipalKind.MEMBERSHIP_USER,
                        true,
                        403,
                        "membership admin action without MEMBERSHIP_ADMIN"),
                aliases(
                        List.of("/membership/points/earn", "/membership/level", "/membership/price-drops/scan"),
                        HttpMethod.POST,
                        PrincipalKind.MEMBERSHIP_MANAGER,
                        true,
                        200,
                        "membership admin action with MEMBERSHIP_ADMIN"));
        Stream<Arguments> csrfAndDefaults = Stream.of(
                arguments(
                        "catalog mutation still requires CSRF",
                        HttpMethod.POST,
                        "/api/v1/catalog/spus",
                        PrincipalKind.PRODUCT_MANAGER,
                        false,
                        403),
                arguments(
                        "owner-facing mutation still requires CSRF",
                        HttpMethod.POST,
                        "/api/v1/marketing/coupons/return",
                        PrincipalKind.USER,
                        false,
                        403),
                arguments(
                        "anonymous owner-facing mutation is unauthorized",
                        HttpMethod.POST,
                        "/api/v1/marketing/coupons/return",
                        PrincipalKind.ANONYMOUS,
                        true,
                        401),
                arguments(
                        "signed payment callback keeps narrow CSRF exemption",
                        HttpMethod.POST,
                        "/api/v1/payments/callback",
                        PrincipalKind.ANONYMOUS,
                        false,
                        200),
                arguments(
                        "signed logistics webhook keeps narrow CSRF exemption",
                        HttpMethod.POST,
                        "/api/logistics/webhook",
                        PrincipalKind.ANONYMOUS,
                        false,
                        200),
                arguments(
                        "legacy unknown API stays denied",
                        HttpMethod.GET,
                        "/api/unknown-matrix-route",
                        PrincipalKind.ORDER_MANAGER,
                        false,
                        403),
                arguments(
                        "canonical unknown API stays denied",
                        HttpMethod.GET,
                        "/api/v1/unknown-matrix-route",
                        PrincipalKind.ORDER_MANAGER,
                        false,
                        403));

        return Stream.of(
                        catalogReads,
                        catalogWrites,
                        marketingActions,
                        marketingAdminReturn,
                        inventoryReleaseDenied,
                        inventoryReleaseAllowed,
                        membershipAdminActions,
                        csrfAndDefaults)
                .flatMap(stream -> stream);
    }

    private static Stream<Arguments> aliases(
            List<String> routeSuffixes,
            HttpMethod method,
            PrincipalKind principal,
            boolean includeCsrf,
            int expectedStatus,
            String description) {
        return routeSuffixes.stream()
                .flatMap(route -> Stream.of("/api" + route, "/api/v1" + route))
                .map(path -> arguments(description + " " + path, method, path, principal, includeCsrf, expectedStatus));
    }

    private static Arguments arguments(
            String description,
            HttpMethod method,
            String path,
            PrincipalKind principal,
            boolean includeCsrf,
            int expectedStatus) {
        return Arguments.of(description, method, path, principal, includeCsrf, expectedStatus);
    }

    private enum PrincipalKind {
        ANONYMOUS(),
        USER("ORDER_CREATE"),
        PRODUCT_MANAGER("PRODUCT_MANAGE"),
        ORDER_MANAGER("ORDER_MANAGE"),
        MEMBERSHIP_USER("MEMBERSHIP_WRITE"),
        MEMBERSHIP_MANAGER("MEMBERSHIP_WRITE", "MEMBERSHIP_ADMIN");

        private final List<String> authorities;

        PrincipalKind(String... authorities) {
            this.authorities = List.of(authorities);
        }

        UsernamePasswordAuthenticationToken authentication() {
            List<SimpleGrantedAuthority> granted = Stream.concat(Stream.of("ROLE_USER"), authorities.stream())
                    .map(SimpleGrantedAuthority::new)
                    .toList();
            return new UsernamePasswordAuthenticationToken(new SessionUser(7L, "USER"), null, granted);
        }
    }

    @RestController
    static class MatrixController {

        @GetMapping({
            "/api/catalog/categories",
            "/api/v1/catalog/categories",
            "/api/catalog/categories/tree",
            "/api/v1/catalog/categories/tree",
            "/api/catalog/spus",
            "/api/v1/catalog/spus",
            "/api/catalog/spus/{id}",
            "/api/v1/catalog/spus/{id}",
            "/api/catalog/skus/{id}",
            "/api/v1/catalog/skus/{id}"
        })
        String catalogRead() {
            return "ok";
        }

        @PostMapping({
            "/api/catalog/spus",
            "/api/v1/catalog/spus",
            "/api/catalog/spus/{id}/status",
            "/api/v1/catalog/spus/{id}/status"
        })
        String catalogWrite() {
            return "ok";
        }

        @PostMapping({
            "/api/marketing/coupons/claim",
            "/api/v1/marketing/coupons/claim",
            "/api/marketing/coupons/redeem",
            "/api/v1/marketing/coupons/redeem",
            "/api/marketing/coupons/return",
            "/api/v1/marketing/coupons/return",
            "/api/marketing/price/quote",
            "/api/v1/marketing/price/quote",
            "/api/marketing/seckill-orders",
            "/api/v1/marketing/seckill-orders",
            "/api/marketing/group-buy/join",
            "/api/v1/marketing/group-buy/join"
        })
        String marketingAction() {
            return "ok";
        }

        @PostMapping({
            "/api/inventory/reservations/{key}/release",
            "/api/v1/inventory/reservations/{key}/release",
            "/api/inventory/compensations",
            "/api/v1/inventory/compensations"
        })
        String inventoryCompensation() {
            return "ok";
        }

        @PostMapping({
            "/api/membership/points/earn",
            "/api/v1/membership/points/earn",
            "/api/membership/level",
            "/api/v1/membership/level",
            "/api/membership/price-drops/scan",
            "/api/v1/membership/price-drops/scan"
        })
        String membershipAdminAction() {
            return "ok";
        }

        @PostMapping({"/api/payments/callback", "/api/v1/payments/callback"})
        String paymentCallback() {
            return "ok";
        }

        @PostMapping({"/api/logistics/webhook", "/api/v1/logistics/webhook"})
        String logisticsWebhook() {
            return "ok";
        }

        @GetMapping({"/api/unknown-matrix-route", "/api/v1/unknown-matrix-route"})
        String unknown() {
            return "ok";
        }
    }
}
