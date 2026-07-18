package com.example.monkey.shared.infrastructure.config;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.monkey.shared.application.observability.AuditService;
import com.example.monkey.shared.application.security.ApiRateLimitApplicationService;
import com.example.monkey.shared.application.security.ApiRateLimitResult;
import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.application.tenant.PermissiveTenantAccessTestConfiguration;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.shared.interfaces.security.ApiRateLimitFilter;
import com.example.monkey.shared.interfaces.web.SessionTokenTransport;
import com.example.monkey.shared.interfaces.web.SpaForwardController;
import com.example.monkey.shared.interfaces.web.VisitInterceptor;
import com.example.monkey.user.domain.SessionTokenService;
import com.example.monkey.user.domain.UserAccountStore;
import com.example.monkey.user.domain.UserAccountStore.UserAccount;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(controllers = {SecurityConfigTest.TestApiController.class, SpaForwardController.class})
@Import({
    SecurityConfig.class,
    ApiRateLimitFilter.class,
    SecurityConfigTest.TestApiController.class,
    PermissiveTenantAccessTestConfiguration.class
})
@TestPropertySource(properties = "app.security.csp.upgrade-insecure-requests=false")
@MockitoBean(
        types = {
            VisitInterceptor.class,
            UserAccountStore.class,
            AuditService.class,
            ApiRateLimitApplicationService.class,
            com.example.monkey.shared.domain.security.TrustedProxyPolicy.class
        })
class SecurityConfigTest {

    private static final Pattern CSP_SCRIPT_NONCE = Pattern.compile("script-src [^;]*'nonce-([^']+)'");
    private static final Pattern CSP_STYLE_NONCE = Pattern.compile("style-src [^;]*'nonce-([^']+)'");

    private final MockMvc mockMvc;
    private final UserAccountStore userAccountStore;
    private final AuditService auditService;
    private final ApiRateLimitApplicationService apiRateLimitService;

    @Autowired
    SecurityConfigTest(
            MockMvc mockMvc,
            UserAccountStore userAccountStore,
            AuditService auditService,
            ApiRateLimitApplicationService apiRateLimitService) {
        this.mockMvc = mockMvc;
        this.userAccountStore = userAccountStore;
        this.auditService = auditService;
        this.apiRateLimitService = apiRateLimitService;
    }

    @BeforeEach
    void allowRateLimitedTraffic() {
        when(apiRateLimitService.consume(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(new ApiRateLimitResult(true, 0));
    }

    @Test
    void passwordEncoderUsesConfiguredBcryptStrength() {
        PasswordEncoder encoder = new SecurityConfig().passwordEncoder();

        String encoded = encoder.encode("StrongerPass123!");

        org.assertj.core.api.Assertions.assertThat(encoded).matches("^\\$2[aby]\\$12\\$.+");
        org.assertj.core.api.Assertions.assertThat(encoder.matches("StrongerPass123!", encoded))
                .isTrue();
    }

    @Test
    void userDetailsServiceMapsAuthoritiesAndCredentialExpiry() {
        when(userAccountStore.findByUsername("jane"))
                .thenReturn(Optional.of(account(
                        "jane",
                        "$2a$12$current",
                        LocalDateTime.now().minusDays(1),
                        List.of("ROLE_USER", "ORDER_CREATE"))));
        when(userAccountStore.findByUsername("expired"))
                .thenReturn(Optional.of(
                        account("expired", "$2a$12$expired", LocalDateTime.now().minusDays(91), List.of("ROLE_USER"))));
        when(userAccountStore.findByUsername("missing")).thenReturn(Optional.empty());

        UserDetailsService service = new SecurityConfig().userDetailsService(userAccountStore);

        UserDetails current = service.loadUserByUsername("jane");
        org.assertj.core.api.Assertions.assertThat(current.getUsername()).isEqualTo("jane");
        org.assertj.core.api.Assertions.assertThat(current.getPassword()).isEqualTo("$2a$12$current");
        org.assertj.core.api.Assertions.assertThat(current.isCredentialsNonExpired())
                .isTrue();
        org.assertj.core.api.Assertions.assertThat(current.getAuthorities())
                .extracting("authority")
                .containsExactlyInAnyOrder("ROLE_USER", "ORDER_CREATE");

        UserDetails expired = service.loadUserByUsername("expired");
        org.assertj.core.api.Assertions.assertThat(expired.isCredentialsNonExpired())
                .isFalse();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.loadUserByUsername("missing"))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessage("User not found");
    }

    @Test
    void jwtBeansAreCreatedFromConfiguredCollaborators() {
        SecurityConfig config = new SecurityConfig();
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<org.springframework.data.redis.core.StringRedisTemplate>
                redisProvider = org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry>
                meterRegistryProvider =
                        org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);

        org.assertj.core.api.Assertions.assertThat(config.jwtTokenService(
                        "ConfigTestJwtSecretValueForHmac!!",
                        900,
                        604800,
                        900,
                        604800,
                        true,
                        false,
                        false,
                        meterRegistryProvider,
                        redisProvider))
                .isNotNull();
        org.assertj.core.api.Assertions.assertThat(config.jwtAuthenticationFilter(
                        org.mockito.Mockito.mock(SessionTokenService.class),
                        org.mockito.Mockito.mock(SessionTokenTransport.class),
                        org.mockito.Mockito.mock(UserAccountStore.class)))
                .isNotNull();
    }

    @Test
    void publicApiResponseIncludesSecurityHeaders() throws Exception {
        mockMvc.perform(get("/api/monkeys").secure(true))
                .andExpect(status().isOk())
                .andExpect(header().string(
                                "Content-Security-Policy", org.hamcrest.Matchers.containsString("default-src 'self'")))
                .andExpect(header().string(
                                "Content-Security-Policy",
                                org.hamcrest.Matchers.containsString("script-src 'self' 'nonce-")))
                .andExpect(header().string(
                                "Content-Security-Policy",
                                org.hamcrest.Matchers.containsString("style-src 'self' 'nonce-")))
                .andExpect(header().string(
                                "Content-Security-Policy",
                                org.hamcrest.Matchers.containsString("https://challenges.cloudflare.com")))
                .andExpect(header().string(
                                "Content-Security-Policy",
                                org.hamcrest.Matchers.containsString(
                                        "connect-src 'self' https://challenges.cloudflare.com")))
                .andExpect(header().string(
                                "Content-Security-Policy",
                                org.hamcrest.Matchers.containsString("frame-src https://challenges.cloudflare.com")))
                .andExpect(header().string(
                                "Content-Security-Policy",
                                org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("unpkg.com"))))
                .andExpect(header().string(
                                "Content-Security-Policy",
                                org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("cdn.jsdelivr.net"))))
                .andExpect(header().string(
                                "Content-Security-Policy",
                                org.hamcrest.Matchers.not(
                                        org.hamcrest.Matchers.containsString("script-src 'self' 'unsafe-inline'"))))
                .andExpect(header().string(
                                "Content-Security-Policy",
                                org.hamcrest.Matchers.not(
                                        org.hamcrest.Matchers.containsString("style-src 'self' 'unsafe-inline'"))))
                .andExpect(header().string(
                                "Content-Security-Policy", org.hamcrest.Matchers.containsString("object-src 'none'")))
                .andExpect(header().string(
                                "Content-Security-Policy", org.hamcrest.Matchers.containsString("base-uri 'self'")))
                .andExpect(header().string(
                                "Content-Security-Policy",
                                org.hamcrest.Matchers.containsString("frame-ancestors 'none'")))
                .andExpect(header().string(
                                "Content-Security-Policy",
                                org.hamcrest.Matchers.not(
                                        org.hamcrest.Matchers.containsString("upgrade-insecure-requests"))))
                .andExpect(header().string(
                                "Strict-Transport-Security", org.hamcrest.Matchers.containsString("max-age=31536000")))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
                .andExpect(
                        header().string("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()"))
                .andExpect(header().string("Cross-Origin-Opener-Policy", "same-origin"))
                .andExpect(header().string("Cross-Origin-Resource-Policy", "same-origin"))
                .andExpect(header().string("X-Permitted-Cross-Domain-Policies", "none"));
    }

    @Test
    void contentSecurityPolicyNonceChangesForEachRequest() throws Exception {
        MvcResult first = mockMvc.perform(get("/api/monkeys").secure(true))
                .andExpect(status().isOk())
                .andReturn();
        MvcResult second = mockMvc.perform(get("/api/monkeys").secure(true))
                .andExpect(status().isOk())
                .andReturn();

        String firstPolicy = first.getResponse().getHeader("Content-Security-Policy");
        String secondPolicy = second.getResponse().getHeader("Content-Security-Policy");
        String firstScriptNonce = extractNonce(CSP_SCRIPT_NONCE, firstPolicy);
        String firstStyleNonce = extractNonce(CSP_STYLE_NONCE, firstPolicy);
        String secondScriptNonce = extractNonce(CSP_SCRIPT_NONCE, secondPolicy);

        org.assertj.core.api.Assertions.assertThat(firstScriptNonce).isNotBlank();
        org.assertj.core.api.Assertions.assertThat(firstStyleNonce).isEqualTo(firstScriptNonce);
        org.assertj.core.api.Assertions.assertThat(secondScriptNonce).isNotEqualTo(firstScriptNonce);
    }

    @Test
    void contentSecurityPolicyNonceIsAvailableToHandlers() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/monkeys").secure(true))
                .andExpect(status().isOk())
                .andReturn();

        String policy = result.getResponse().getHeader("Content-Security-Policy");
        String nonce = extractNonce(CSP_SCRIPT_NONCE, policy);
        org.assertj.core.api.Assertions.assertThat(result.getRequest().getAttribute(SecurityConfig.CSP_NONCE_ATTRIBUTE))
                .isEqualTo(nonce);
    }

    @Test
    void unsafeAdminEndpointRejectsMissingCsrfToken() throws Exception {
        expectProblem(
                mockMvc.perform(post("/api/monkeys/add").with(authenticatedUser(1L, "ADMIN"))),
                403,
                "FORBIDDEN",
                ErrorCode.FORBIDDEN.defaultMessage(),
                "/api/monkeys/add");
    }

    @Test
    void missingCsrfIsForbiddenBeforeAuthenticationChallenge() throws Exception {
        expectProblem(
                mockMvc.perform(post("/api/orders/create")),
                403,
                "FORBIDDEN",
                ErrorCode.FORBIDDEN.defaultMessage(),
                "/api/orders/create");
    }

    @Test
    void publicAuthCallbacksAndTrackingPostsDoNotRequireCsrfToken() throws Exception {
        for (String path : List.of(
                "/api/auth/login",
                "/api/v1/auth/login",
                "/api/auth/register",
                "/api/v1/auth/register",
                "/api/auth/refresh",
                "/api/v1/auth/refresh",
                "/api/auth/reset-password",
                "/api/v1/auth/reset-password",
                "/api/auth/reset-password/request",
                "/api/v1/auth/reset-password/request",
                "/api/tracking/events",
                "/api/v1/tracking/events")) {
            mockMvc.perform(post(path).secure(true)).andExpect(status().isOk());
        }
    }

    @ParameterizedTest
    @MethodSource("spaRoutes")
    void spaRoutesForwardToIndexWithoutAuthentication(String path) throws Exception {
        mockMvc.perform(get(path).secure(true)).andExpect(status().isOk());
    }

    @Test
    void legacyFaviconRedirectsToSvgAsset() throws Exception {
        mockMvc.perform(get("/favicon.ico").secure(true))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/favicon.svg"));
    }

    @Test
    void unsafeAdminEndpointRequiresProductManageAuthority() throws Exception {
        mockMvc.perform(post("/api/monkeys/add").with(csrf()).with(authenticatedUser(7L, "USER")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(post("/api/monkeys/add").with(csrf()).with(authenticatedUser(1L, "ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    void uploadEndpointRequiresUploadAuthority() throws Exception {
        expectProblem(
                mockMvc.perform(post("/api/upload").with(csrf())),
                401,
                "UNAUTHORIZED",
                ErrorCode.UNAUTHORIZED.defaultMessage(),
                "/api/upload");

        mockMvc.perform(post("/api/upload").with(csrf()).with(authenticatedUser(7L, "USER")))
                .andExpect(status().isOk());
    }

    @Test
    void roleOnlyPrincipalCannotPassPermissionProtectedRoutes() throws Exception {
        mockMvc.perform(post("/api/orders/create").with(csrf()).with(roleOnlyUser(7L, "USER")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(post("/api/monkeys/add").with(csrf()).with(roleOnlyUser(1L, "ADMIN")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(get("/api/upload/presigned-get").with(roleOnlyUser(7L, "USER")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void unknownApiRoutesAreDeniedByDefault() throws Exception {
        expectProblem(
                mockMvc.perform(get("/api/unknown").with(authenticatedUser(1L, "ADMIN"))),
                403,
                "FORBIDDEN",
                ErrorCode.FORBIDDEN.defaultMessage(),
                "/api/unknown");
        expectProblem(
                mockMvc.perform(get("/api/v1/unknown").with(authenticatedUser(1L, "ADMIN"))),
                403,
                "FORBIDDEN",
                ErrorCode.FORBIDDEN.defaultMessage(),
                "/api/v1/unknown");
    }

    @Test
    void logoutIsPostOnlyAndRequiresCsrf() throws Exception {
        mockMvc.perform(get("/api/user/logout").with(authenticatedUser(7L, "USER")))
                .andExpect(status().isMethodNotAllowed());

        mockMvc.perform(post("/api/user/logout").with(authenticatedUser(7L, "USER")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());

        mockMvc.perform(post("/api/user/logout").with(csrf()).with(authenticatedUser(7L, "USER")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.message").value("ok"))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.traceId").isNotEmpty());

        mockMvc.perform(post("/api/user/logout").with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.title").value(ErrorCode.UNAUTHORIZED.defaultMessage()))
                .andExpect(jsonPath("$.detail").value(ErrorCode.UNAUTHORIZED.defaultMessage()))
                .andExpect(jsonPath("$.instance").value("/api/user/logout"))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());

        verify(auditService)
                .record(AuditService.LOGOUT_SUCCESS, AuditService.OUTCOME_SUCCESS, 7L, "USER", null, "127.0.0.1", null);
        verify(auditService)
                .record(
                        AuditService.LOGOUT_FAILURE,
                        AuditService.OUTCOME_FAILURE,
                        null,
                        null,
                        null,
                        "127.0.0.1",
                        "missing_authentication");
    }

    @Test
    void securityPrincipalIsExposedAsAuthenticationPrincipal() throws Exception {
        mockMvc.perform(get("/api/user/me").with(authenticatedUser(7L, "USER")))
                .andExpect(status().isOk())
                .andExpect(content().string("7:USER"));
    }

    @Test
    void passwordChangeRequiredPrincipalCanOnlyReachChangePasswordFlow() throws Exception {
        mockMvc.perform(post("/api/orders/create").with(csrf()).with(passwordChangeRequiredUser(7L, "USER")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.title").value(ErrorCode.FORBIDDEN.defaultMessage()))
                .andExpect(jsonPath("$.detail").value(SecurityConfig.PASSWORD_CHANGE_REQUIRED))
                .andExpect(jsonPath("$.instance").value("/api/orders/create"))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());

        mockMvc.perform(post("/api/user/update-password").with(csrf()).with(passwordChangeRequiredUser(7L, "USER")))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/login").with(csrf()).with(passwordChangeRequiredUser(7L, "USER")))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/user/me").with(passwordChangeRequiredUser(7L, "USER")))
                .andExpect(status().isOk())
                .andExpect(content().string("7:USER"));

        mockMvc.perform(post("/api/tracking/events").with(csrf()).with(passwordChangeRequiredUser(7L, "USER")))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/address").with(passwordChangeRequiredUser(7L, "USER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value(SecurityConfig.PASSWORD_CHANGE_REQUIRED));

        mockMvc.perform(post("/api/v1/orders/create").with(csrf()).with(passwordChangeRequiredUser(7L, "USER")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(SecurityConfig.PASSWORD_CHANGE_REQUIRED))
                .andExpect(jsonPath("$.instance").value("/api/v1/orders/create"));

        mockMvc.perform(post("/api/v1/users/update-password").with(csrf()).with(passwordChangeRequiredUser(7L, "USER")))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/login").with(csrf()).with(passwordChangeRequiredUser(7L, "USER")))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/users/me").with(passwordChangeRequiredUser(7L, "USER")))
                .andExpect(status().isOk())
                .andExpect(content().string("7:USER"));

        mockMvc.perform(post("/api/v1/tracking/events").with(csrf()).with(passwordChangeRequiredUser(7L, "USER")))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/addresses").with(passwordChangeRequiredUser(7L, "USER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value(SecurityConfig.PASSWORD_CHANGE_REQUIRED));
    }

    @ParameterizedTest(name = "{0} {1} anonymous={2}, user={3}, admin={4}")
    @MethodSource("rbacRoutes")
    void rbacAuthorizationMatrixIsExplicitlyCovered(
            HttpMethod method, String path, int anonymousStatus, int userStatus, int adminStatus) throws Exception {
        assertRouteStatus(method, path, null, anonymousStatus);
        assertRouteStatus(method, path, "USER", userStatus);
        assertRouteStatus(method, path, "ADMIN", adminStatus);
    }

    @Test
    void staleTenantReadAuthorityCannotPromoteAUserTokenToPlatformAdmin() throws Exception {
        UsernamePasswordAuthenticationToken staleUserToken = new UsernamePasswordAuthenticationToken(
                new SessionUser(7L, "USER"),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("TENANT_READ")));

        mockMvc.perform(get("/api/tenants").with(authentication(staleUserToken)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/tenants").with(authenticatedUser(1L, "ADMIN")))
                .andExpect(status().isOk());
    }

    private static Stream<Arguments> rbacRoutes() {
        return Stream.concat(legacyRbacRoutes(), versionedRbacRoutes());
    }

    private static Stream<String> spaRoutes() {
        return Stream.of(
                "/",
                "/login",
                "/shop",
                "/shop/1",
                "/search",
                "/recommendations",
                "/cart",
                "/checkout",
                "/orders",
                "/orders/1/review",
                "/payment",
                "/payment/1",
                "/logistics",
                "/logistics/1",
                "/membership",
                "/profile",
                "/admin",
                "/inventory",
                "/marketing",
                "/risk",
                "/dashboard",
                "/tenants");
    }

    private static Stream<Arguments> legacyRbacRoutes() {
        return Stream.of(
                publicRoute(HttpMethod.GET, "/api/monkeys"),
                publicRoute(HttpMethod.GET, "/api/user/me"),
                publicRoute(HttpMethod.GET, "/api/auth/captcha/config"),
                publicRoute(HttpMethod.GET, "/actuator/prometheus"),
                publicRoute(HttpMethod.POST, "/api/auth/login"),
                publicRoute(HttpMethod.POST, "/api/auth/register"),
                publicRoute(HttpMethod.POST, "/api/auth/refresh"),
                publicRoute(HttpMethod.POST, "/api/auth/reset-password"),
                publicRoute(HttpMethod.POST, "/api/auth/reset-password/request"),
                authenticatedRoute(HttpMethod.GET, "/api/address"),
                authenticatedRoute(HttpMethod.GET, "/api/address/list"),
                authenticatedRoute(HttpMethod.POST, "/api/address"),
                authenticatedRoute(HttpMethod.POST, "/api/address/set-default/1"),
                authenticatedRoute(HttpMethod.DELETE, "/api/address/1"),
                authenticatedRoute(HttpMethod.GET, "/api/user/profile"),
                authenticatedRoute(HttpMethod.POST, "/api/user/update-password"),
                authenticatedRoute(HttpMethod.POST, "/api/user/forget-me"),
                authenticatedRoute(HttpMethod.GET, "/api/membership/dashboard"),
                authenticatedRoute(HttpMethod.POST, "/api/membership/check-in"),
                publicRoute(HttpMethod.GET, "/api/search/products"),
                publicRoute(HttpMethod.GET, "/api/search/suggestions"),
                publicRoute(HttpMethod.GET, "/api/search/hot"),
                authenticatedRoute(HttpMethod.GET, "/api/search/recommendations"),
                authenticatedRoute(HttpMethod.POST, "/api/search/profile"),
                authenticatedRoute(HttpMethod.POST, "/api/search/conversions"),
                authenticatedRoute(HttpMethod.POST, "/api/risk/assess"),
                adminRoute(HttpMethod.GET, "/api/risk/reviews"),
                adminRoute(HttpMethod.POST, "/api/risk/reviews/1/resolve"),
                publicRoute(HttpMethod.POST, "/api/tracking/events"),
                authenticatedRoute(HttpMethod.GET, "/api/tracking/profile/me"),
                adminRoute(HttpMethod.GET, "/api/tracking/dashboard"),
                adminRoute(HttpMethod.GET, "/api/tracking/funnel"),
                adminRoute(HttpMethod.GET, "/api/tracking/profile/7"),
                adminRoute(HttpMethod.GET, "/api/tracking/products/42"),
                adminRoute(HttpMethod.GET, "/api/tenants"),
                adminRoute(HttpMethod.GET, "/api/tenants/dashboard"),
                adminRoute(HttpMethod.POST, "/api/tenants"),
                adminRoute(HttpMethod.POST, "/api/tenants/200/renew"),
                adminRoute(HttpMethod.POST, "/api/tenants/200/downgrade"),
                adminRoute(HttpMethod.GET, "/api/tenants/200/configs"),
                adminRoute(HttpMethod.PUT, "/api/tenants/200/configs"),
                adminRoute(HttpMethod.POST, "/api/tenants/200/bills"),
                adminRoute(HttpMethod.GET, "/api/tenants/200/bills"),
                adminRoute(HttpMethod.POST, "/api/tenants/200/exports"),
                adminRoute(HttpMethod.GET, "/api/tenants/200/exports"),
                authenticatedRoute(HttpMethod.POST, "/api/orders/create"),
                authenticatedRoute(HttpMethod.GET, "/api/orders/my"),
                authenticatedRoute(HttpMethod.POST, "/api/orders/receive/1"),
                authenticatedRoute(HttpMethod.POST, "/api/orders/return/apply/1"),
                authenticatedRoute(HttpMethod.POST, "/api/orders/return/ship/1"),
                authenticatedRoute(HttpMethod.POST, "/api/upload"),
                authenticatedRoute(HttpMethod.GET, "/api/upload/presigned-get"),
                adminRoute(HttpMethod.POST, "/api/monkeys/add"),
                adminRoute(HttpMethod.POST, "/api/monkeys/update"),
                adminRoute(HttpMethod.GET, "/api/stats/summary"),
                adminRoute(HttpMethod.GET, "/api/stats/audit-trace"),
                adminRoute(HttpMethod.GET, "/api/orders/all"),
                adminRoute(HttpMethod.POST, "/api/orders/ship/1"),
                adminRoute(HttpMethod.POST, "/api/orders/return/approve/1"),
                adminRoute(HttpMethod.POST, "/api/orders/return/confirm/1"),
                adminRoute(HttpMethod.POST, "/api/membership/price-drops/scan"),
                adminRoute(HttpMethod.DELETE, "/api/monkeys/1"),
                authenticatedRoute(HttpMethod.DELETE, "/api/orders/1"));
    }

    private static Stream<Arguments> versionedRbacRoutes() {
        return Stream.of(
                publicRoute(HttpMethod.GET, "/api/v1/monkeys"),
                publicRoute(HttpMethod.GET, "/api/v1/users/me"),
                publicRoute(HttpMethod.GET, "/api/v1/auth/captcha/config"),
                publicRoute(HttpMethod.GET, "/api/v1/openapi"),
                publicRoute(HttpMethod.GET, "/api/v1/docs"),
                publicRoute(HttpMethod.POST, "/api/v1/auth/login"),
                publicRoute(HttpMethod.POST, "/api/v1/auth/register"),
                publicRoute(HttpMethod.POST, "/api/v1/auth/refresh"),
                publicRoute(HttpMethod.POST, "/api/v1/auth/reset-password"),
                publicRoute(HttpMethod.POST, "/api/v1/auth/reset-password/request"),
                authenticatedRoute(HttpMethod.GET, "/api/v1/addresses"),
                authenticatedRoute(HttpMethod.GET, "/api/v1/addresses/list"),
                authenticatedRoute(HttpMethod.POST, "/api/v1/addresses"),
                authenticatedRoute(HttpMethod.POST, "/api/v1/addresses/set-default/1"),
                authenticatedRoute(HttpMethod.DELETE, "/api/v1/addresses/1"),
                authenticatedRoute(HttpMethod.GET, "/api/v1/users/profile"),
                authenticatedRoute(HttpMethod.POST, "/api/v1/users/update-password"),
                authenticatedRoute(HttpMethod.POST, "/api/v1/users/forget-me"),
                authenticatedRoute(HttpMethod.GET, "/api/v1/membership/dashboard"),
                authenticatedRoute(HttpMethod.POST, "/api/v1/membership/check-in"),
                publicRoute(HttpMethod.GET, "/api/v1/search/products"),
                publicRoute(HttpMethod.GET, "/api/v1/search/suggestions"),
                publicRoute(HttpMethod.GET, "/api/v1/search/hot"),
                authenticatedRoute(HttpMethod.GET, "/api/v1/search/recommendations"),
                authenticatedRoute(HttpMethod.POST, "/api/v1/search/profile"),
                authenticatedRoute(HttpMethod.POST, "/api/v1/search/conversions"),
                authenticatedRoute(HttpMethod.POST, "/api/v1/risk/assess"),
                adminRoute(HttpMethod.GET, "/api/v1/risk/reviews"),
                adminRoute(HttpMethod.POST, "/api/v1/risk/reviews/1/resolve"),
                publicRoute(HttpMethod.POST, "/api/v1/tracking/events"),
                authenticatedRoute(HttpMethod.GET, "/api/v1/tracking/profile/me"),
                adminRoute(HttpMethod.GET, "/api/v1/tracking/dashboard"),
                adminRoute(HttpMethod.GET, "/api/v1/tracking/funnel"),
                adminRoute(HttpMethod.GET, "/api/v1/tracking/profile/7"),
                adminRoute(HttpMethod.GET, "/api/v1/tracking/products/42"),
                adminRoute(HttpMethod.GET, "/api/v1/tenants"),
                adminRoute(HttpMethod.GET, "/api/v1/tenants/dashboard"),
                adminRoute(HttpMethod.POST, "/api/v1/tenants"),
                adminRoute(HttpMethod.POST, "/api/v1/tenants/200/renew"),
                adminRoute(HttpMethod.POST, "/api/v1/tenants/200/downgrade"),
                adminRoute(HttpMethod.GET, "/api/v1/tenants/200/configs"),
                adminRoute(HttpMethod.PUT, "/api/v1/tenants/200/configs"),
                adminRoute(HttpMethod.POST, "/api/v1/tenants/200/bills"),
                adminRoute(HttpMethod.GET, "/api/v1/tenants/200/bills"),
                adminRoute(HttpMethod.POST, "/api/v1/tenants/200/exports"),
                adminRoute(HttpMethod.GET, "/api/v1/tenants/200/exports"),
                authenticatedRoute(HttpMethod.POST, "/api/v1/orders/create"),
                authenticatedRoute(HttpMethod.GET, "/api/v1/orders/my"),
                authenticatedRoute(HttpMethod.POST, "/api/v1/orders/receive/1"),
                authenticatedRoute(HttpMethod.POST, "/api/v1/orders/return/apply/1"),
                authenticatedRoute(HttpMethod.POST, "/api/v1/orders/return/ship/1"),
                authenticatedRoute(HttpMethod.POST, "/api/v1/uploads"),
                authenticatedRoute(HttpMethod.GET, "/api/v1/uploads/presigned-get"),
                adminRoute(HttpMethod.POST, "/api/v1/monkeys/add"),
                adminRoute(HttpMethod.POST, "/api/v1/monkeys/update"),
                adminRoute(HttpMethod.GET, "/api/v1/stats/summary"),
                adminRoute(HttpMethod.GET, "/api/v1/stats/audit-trace"),
                adminRoute(HttpMethod.GET, "/api/v1/orders/all"),
                adminRoute(HttpMethod.POST, "/api/v1/orders/ship/1"),
                adminRoute(HttpMethod.POST, "/api/v1/orders/return/approve/1"),
                adminRoute(HttpMethod.POST, "/api/v1/orders/return/confirm/1"),
                adminRoute(HttpMethod.POST, "/api/v1/membership/price-drops/scan"),
                adminRoute(HttpMethod.DELETE, "/api/v1/monkeys/1"),
                authenticatedRoute(HttpMethod.DELETE, "/api/v1/orders/1"));
    }

    private static Arguments publicRoute(HttpMethod method, String path) {
        return Arguments.of(method, path, 200, 200, 200);
    }

    private static Arguments authenticatedRoute(HttpMethod method, String path) {
        return Arguments.of(method, path, 401, 200, 200);
    }

    private static Arguments adminRoute(HttpMethod method, String path) {
        return Arguments.of(method, path, 401, 403, 200);
    }

    @RestController
    @SuppressWarnings("unused")
    public static class TestApiController {

        @GetMapping({"/api/monkeys", "/api/v1/monkeys"})
        String monkeys() {
            return "ok";
        }

        @PostMapping({"/api/monkeys/add", "/api/v1/monkeys/add"})
        String addMonkey() {
            return "ok";
        }

        @PostMapping({"/api/monkeys/update", "/api/v1/monkeys/update"})
        String updateMonkey() {
            return "ok";
        }

        @DeleteMapping({"/api/monkeys/{id}", "/api/v1/monkeys/{id}"})
        String deleteMonkey() {
            return "ok";
        }

        @PostMapping({"/api/upload", "/api/v1/uploads"})
        String upload() {
            return "ok";
        }

        @GetMapping({"/api/upload/presigned-get", "/api/v1/uploads/presigned-get"})
        String presignedGet() {
            return "ok";
        }

        @PostMapping({"/api/user/logout", "/api/v1/users/logout"})
        String logout() {
            return "ok";
        }

        @GetMapping({"/api/user/me", "/api/v1/users/me"})
        String me(@AuthenticationPrincipal SessionUser currentUser) {
            return currentUser == null ? "anonymous" : currentUser.id() + ":" + currentUser.role();
        }

        @PostMapping({
            "/api/auth/login",
            "/api/auth/register",
            "/api/auth/refresh",
            "/api/auth/reset-password",
            "/api/auth/reset-password/request",
            "/api/v1/auth/login",
            "/api/v1/auth/register",
            "/api/v1/auth/refresh",
            "/api/v1/auth/reset-password",
            "/api/v1/auth/reset-password/request"
        })
        String auth() {
            return "ok";
        }

        @GetMapping({"/api/auth/captcha/config", "/api/v1/auth/captcha/config"})
        String captchaConfig() {
            return "ok";
        }

        @GetMapping("/actuator/prometheus")
        String prometheus() {
            return "metrics";
        }

        @GetMapping({"/api/address/list", "/api/v1/addresses/list"})
        String addresses() {
            return "ok";
        }

        @GetMapping({"/api/address", "/api/v1/addresses"})
        String addressRoot() {
            return "ok";
        }

        @PostMapping({"/api/address", "/api/v1/addresses"})
        String addAddress() {
            return "ok";
        }

        @PostMapping({"/api/address/set-default/{id}", "/api/v1/addresses/set-default/{id}"})
        String setDefaultAddress() {
            return "ok";
        }

        @DeleteMapping({"/api/address/{id}", "/api/v1/addresses/{id}"})
        String deleteAddress() {
            return "ok";
        }

        @GetMapping({"/api/user/profile", "/api/v1/users/profile"})
        String profile() {
            return "ok";
        }

        @PostMapping({"/api/user/update-password", "/api/v1/users/update-password"})
        String updatePassword() {
            return "ok";
        }

        @PostMapping({"/api/user/forget-me", "/api/v1/users/forget-me"})
        String forgetMe() {
            return "ok";
        }

        @GetMapping({"/api/membership/dashboard", "/api/v1/membership/dashboard"})
        String membershipDashboard() {
            return "ok";
        }

        @PostMapping({"/api/membership/check-in", "/api/v1/membership/check-in"})
        String membershipCheckIn() {
            return "ok";
        }

        @PostMapping({"/api/membership/price-drops/scan", "/api/v1/membership/price-drops/scan"})
        String membershipPriceDropScan() {
            return "ok";
        }

        @GetMapping({"/api/search/products", "/api/v1/search/products"})
        String searchProducts() {
            return "ok";
        }

        @GetMapping({"/api/search/suggestions", "/api/v1/search/suggestions"})
        String searchSuggestions() {
            return "ok";
        }

        @GetMapping({"/api/search/hot", "/api/v1/search/hot"})
        String searchHot() {
            return "ok";
        }

        @GetMapping({"/api/search/recommendations", "/api/v1/search/recommendations"})
        String searchRecommendations() {
            return "ok";
        }

        @PostMapping({"/api/search/profile", "/api/v1/search/profile"})
        String searchProfile() {
            return "ok";
        }

        @PostMapping({"/api/search/conversions", "/api/v1/search/conversions"})
        String searchConversions() {
            return "ok";
        }

        @PostMapping({"/api/risk/assess", "/api/v1/risk/assess"})
        String riskAssess() {
            return "ok";
        }

        @GetMapping({"/api/risk/reviews", "/api/v1/risk/reviews"})
        String riskReviews() {
            return "ok";
        }

        @PostMapping({"/api/risk/reviews/{id}/resolve", "/api/v1/risk/reviews/{id}/resolve"})
        String riskResolve() {
            return "ok";
        }

        @PostMapping({"/api/tracking/events", "/api/v1/tracking/events"})
        String trackingEvents() {
            return "ok";
        }

        @GetMapping({"/api/tracking/profile/me", "/api/v1/tracking/profile/me"})
        String trackingProfileMe() {
            return "ok";
        }

        @GetMapping({"/api/tracking/dashboard", "/api/v1/tracking/dashboard"})
        String trackingDashboard() {
            return "ok";
        }

        @GetMapping({"/api/tracking/funnel", "/api/v1/tracking/funnel"})
        String trackingFunnel() {
            return "ok";
        }

        @GetMapping({"/api/tracking/profile/{id}", "/api/v1/tracking/profile/{id}"})
        String trackingProfile() {
            return "ok";
        }

        @GetMapping({"/api/tracking/products/{id}", "/api/v1/tracking/products/{id}"})
        String trackingProduct() {
            return "ok";
        }

        @GetMapping({"/api/tenants", "/api/v1/tenants"})
        String tenants() {
            return "ok";
        }

        @GetMapping({"/api/tenants/dashboard", "/api/v1/tenants/dashboard"})
        String tenantDashboard() {
            return "ok";
        }

        @PostMapping({"/api/tenants", "/api/v1/tenants"})
        String createTenant() {
            return "ok";
        }

        @PostMapping({"/api/tenants/{id}/renew", "/api/v1/tenants/{id}/renew"})
        String renewTenant() {
            return "ok";
        }

        @PostMapping({"/api/tenants/{id}/downgrade", "/api/v1/tenants/{id}/downgrade"})
        String downgradeTenant() {
            return "ok";
        }

        @GetMapping({"/api/tenants/{id}/configs", "/api/v1/tenants/{id}/configs"})
        String tenantConfigs() {
            return "ok";
        }

        @PutMapping({"/api/tenants/{id}/configs", "/api/v1/tenants/{id}/configs"})
        String upsertTenantConfig() {
            return "ok";
        }

        @PostMapping({"/api/tenants/{id}/bills", "/api/v1/tenants/{id}/bills"})
        String generateTenantBill() {
            return "ok";
        }

        @GetMapping({"/api/tenants/{id}/bills", "/api/v1/tenants/{id}/bills"})
        String tenantBills() {
            return "ok";
        }

        @PostMapping({"/api/tenants/{id}/exports", "/api/v1/tenants/{id}/exports"})
        String requestTenantExport() {
            return "ok";
        }

        @GetMapping({"/api/tenants/{id}/exports", "/api/v1/tenants/{id}/exports"})
        String tenantExports() {
            return "ok";
        }

        @PostMapping({"/api/orders/create", "/api/v1/orders/create"})
        String createOrder() {
            return "ok";
        }

        @GetMapping({"/api/orders/my", "/api/v1/orders/my"})
        String myOrders() {
            return "ok";
        }

        @GetMapping({"/api/orders/all", "/api/v1/orders/all"})
        String allOrders() {
            return "ok";
        }

        @PostMapping({"/api/orders/ship/{id}", "/api/v1/orders/ship/{id}"})
        String shipOrder() {
            return "ok";
        }

        @PostMapping({"/api/orders/receive/{id}", "/api/v1/orders/receive/{id}"})
        String receiveOrder() {
            return "ok";
        }

        @PostMapping({"/api/orders/return/apply/{id}", "/api/v1/orders/return/apply/{id}"})
        String applyReturn() {
            return "ok";
        }

        @PostMapping({"/api/orders/return/approve/{id}", "/api/v1/orders/return/approve/{id}"})
        String approveReturn() {
            return "ok";
        }

        @PostMapping({"/api/orders/return/ship/{id}", "/api/v1/orders/return/ship/{id}"})
        String shipReturn() {
            return "ok";
        }

        @PostMapping({"/api/orders/return/confirm/{id}", "/api/v1/orders/return/confirm/{id}"})
        String confirmReturn() {
            return "ok";
        }

        @DeleteMapping({"/api/orders/{id}", "/api/v1/orders/{id}"})
        String deleteOrder() {
            return "ok";
        }

        @GetMapping({"/api/stats/summary", "/api/v1/stats/summary"})
        String stats() {
            return "ok";
        }

        @GetMapping({"/api/stats/audit-trace", "/api/v1/stats/audit-trace"})
        String auditTrace() {
            return "ok";
        }

        @GetMapping({"/api/v1/openapi", "/api/v1/docs"})
        String openApi() {
            return "ok";
        }

        @GetMapping({"/api/unknown", "/api/v1/unknown"})
        String unknown() {
            return "ok";
        }
    }

    private static String extractNonce(Pattern pattern, String policy) {
        Matcher matcher = pattern.matcher(policy);
        org.assertj.core.api.Assertions.assertThat(matcher.find())
                .as("CSP nonce should exist in policy: " + policy)
                .isTrue();
        return matcher.group(1);
    }

    private void assertRouteStatus(HttpMethod method, String path, String role, int expectedStatus) throws Exception {
        MockHttpServletRequestBuilder builder = request(method, path).secure(true);
        if (requiresCsrf(method)) {
            builder.with(csrf());
        }
        if (role != null) {
            builder.with(authenticatedUser(SessionIdentityForTest.id(role), role));
        }
        ResultActions result = mockMvc.perform(builder).andExpect(status().is(expectedStatus));
        if (expectedStatus == 401) {
            expectProblemBody(result, 401, "UNAUTHORIZED", ErrorCode.UNAUTHORIZED.defaultMessage(), path);
        } else if (expectedStatus == 403) {
            expectProblemBody(result, 403, "FORBIDDEN", ErrorCode.FORBIDDEN.defaultMessage(), path);
        }
    }

    private static void expectProblem(
            ResultActions actions, int expectedStatus, String code, String detail, String instance) throws Exception {
        expectProblemBody(actions.andExpect(status().is(expectedStatus)), expectedStatus, code, detail, instance);
    }

    private static void expectProblemBody(
            ResultActions actions, int expectedStatus, String code, String detail, String instance) throws Exception {
        actions.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(expectedStatus))
                .andExpect(jsonPath("$.title").value(detail))
                .andExpect(jsonPath("$.detail").value(detail))
                .andExpect(jsonPath("$.instance").value(instance))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    private static boolean requiresCsrf(HttpMethod method) {
        return method == HttpMethod.POST
                || method == HttpMethod.PUT
                || method == HttpMethod.PATCH
                || method == HttpMethod.DELETE;
    }

    private static RequestPostProcessor authenticatedUser(Long id, String role) {
        UsernamePasswordAuthenticationToken token =
                new UsernamePasswordAuthenticationToken(new SessionUser(id, role), null, authoritiesForRole(role));
        return authentication(token);
    }

    private static RequestPostProcessor passwordChangeRequiredUser(Long id, String role) {
        UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(
                new SessionUser(id, role, true), null, authoritiesForRole(role));
        return authentication(token);
    }

    private static RequestPostProcessor roleOnlyUser(Long id, String role) {
        UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(
                new SessionUser(id, role), null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        return authentication(token);
    }

    private static UserAccount account(
            String username, String passwordHash, LocalDateTime passwordLastChangedAt, List<String> authorities) {
        return new UserAccount(
                9L,
                username,
                passwordHash,
                "188****8888",
                username + "@example.com",
                null,
                "USER",
                username,
                passwordLastChangedAt,
                false,
                null,
                false,
                authorities);
    }

    private static List<SimpleGrantedAuthority> authoritiesForRole(String role) {
        if ("ADMIN".equals(role)) {
            return List.of(
                    new SimpleGrantedAuthority("ROLE_ADMIN"),
                    new SimpleGrantedAuthority("USER_PROFILE_READ"),
                    new SimpleGrantedAuthority("USER_PROFILE_WRITE"),
                    new SimpleGrantedAuthority("ADDRESS_MANAGE"),
                    new SimpleGrantedAuthority("ORDER_CREATE"),
                    new SimpleGrantedAuthority("ORDER_READ_OWN"),
                    new SimpleGrantedAuthority("ORDER_RETURN_REQUEST"),
                    new SimpleGrantedAuthority("UPLOAD_AVATAR"),
                    new SimpleGrantedAuthority("ADMIN_DASHBOARD_READ"),
                    new SimpleGrantedAuthority("PRODUCT_MANAGE"),
                    new SimpleGrantedAuthority("ORDER_MANAGE"),
                    new SimpleGrantedAuthority("MEMBERSHIP_READ"),
                    new SimpleGrantedAuthority("MEMBERSHIP_WRITE"),
                    new SimpleGrantedAuthority("MEMBERSHIP_ADMIN"),
                    new SimpleGrantedAuthority("SEARCH_READ"),
                    new SimpleGrantedAuthority("SEARCH_WRITE"),
                    new SimpleGrantedAuthority("SEARCH_ADMIN"),
                    new SimpleGrantedAuthority("RISK_READ"),
                    new SimpleGrantedAuthority("RISK_WRITE"),
                    new SimpleGrantedAuthority("RISK_REVIEW"),
                    new SimpleGrantedAuthority("TRACKING_READ"),
                    new SimpleGrantedAuthority("TRACKING_WRITE"),
                    new SimpleGrantedAuthority("TRACKING_ADMIN"),
                    new SimpleGrantedAuthority("TENANT_READ"),
                    new SimpleGrantedAuthority("TENANT_WRITE"),
                    new SimpleGrantedAuthority("TENANT_ADMIN"),
                    new SimpleGrantedAuthority("UPLOAD_PRODUCT_IMAGE"));
        }
        return List.of(
                new SimpleGrantedAuthority("ROLE_USER"),
                new SimpleGrantedAuthority("USER_PROFILE_READ"),
                new SimpleGrantedAuthority("USER_PROFILE_WRITE"),
                new SimpleGrantedAuthority("ADDRESS_MANAGE"),
                new SimpleGrantedAuthority("ORDER_CREATE"),
                new SimpleGrantedAuthority("ORDER_READ_OWN"),
                new SimpleGrantedAuthority("ORDER_RETURN_REQUEST"),
                new SimpleGrantedAuthority("MEMBERSHIP_READ"),
                new SimpleGrantedAuthority("MEMBERSHIP_WRITE"),
                new SimpleGrantedAuthority("SEARCH_READ"),
                new SimpleGrantedAuthority("SEARCH_WRITE"),
                new SimpleGrantedAuthority("RISK_READ"),
                new SimpleGrantedAuthority("RISK_WRITE"),
                new SimpleGrantedAuthority("TRACKING_READ"),
                new SimpleGrantedAuthority("TRACKING_WRITE"),
                new SimpleGrantedAuthority("UPLOAD_AVATAR"));
    }

    private static final class SessionIdentityForTest {

        private SessionIdentityForTest() {}

        static Long id(String role) {
            return "ADMIN".equals(role) ? 1L : 7L;
        }
    }
}
