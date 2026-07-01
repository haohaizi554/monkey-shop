package com.example.monkey.shared.infrastructure.config;

import com.example.monkey.shared.application.observability.AuditService;
import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.shared.interfaces.dto.Result;
import com.example.monkey.shared.interfaces.security.ApiRateLimitFilter;
import com.example.monkey.shared.interfaces.web.ApiPaths;
import com.example.monkey.shared.interfaces.web.ClientIps;
import com.example.monkey.shared.interfaces.web.CsrfCookieFilter;
import com.example.monkey.shared.interfaces.web.ErrorHttpStatuses;
import com.example.monkey.shared.interfaces.web.ProblemDetails;
import com.example.monkey.shared.interfaces.web.SessionTokenTransport;
import com.example.monkey.shared.interfaces.web.UserMdcFilter;
import com.example.monkey.user.domain.SessionTokenService;
import com.example.monkey.user.domain.UserAccountStore;
import com.example.monkey.user.domain.UserAccountStore.UserAccount;
import com.example.monkey.user.infrastructure.JwtAuthenticationFilter;
import com.example.monkey.user.infrastructure.JwtTokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.LogoutFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.header.HeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
@EnableMethodSecurity
@EnableWebSecurity
public class SecurityConfig {

    static final String CSP_NONCE_ATTRIBUTE = "cspNonce";
    static final String PASSWORD_CHANGE_REQUIRED = "password change required";
    private static final AuthenticationTrustResolver TRUST_RESOLVER = new AuthenticationTrustResolverImpl();

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public UserDetailsService userDetailsService(UserAccountStore userAccountStore) {
        return username -> userAccountStore
                .findByUsername(username)
                .map(SecurityConfig::toUserDetails)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
    }

    @Bean
    public JwtTokenService jwtTokenService(
            @Value("${app.jwt.secret:}") String rawSecret,
            @Value("${app.jwt.access-token-ttl-seconds:900}") long accessTokenTtlSeconds,
            @Value("${app.jwt.refresh-token-ttl-seconds:604800}") long refreshTokenTtlSeconds,
            @Value("${app.jwt.access-cookie-max-age-seconds:900}") long accessCookieMaxAgeSeconds,
            @Value("${app.jwt.refresh-cookie-max-age-seconds:604800}") long refreshCookieMaxAgeSeconds,
            @Value("${app.jwt.cookie-secure:${SESSION_COOKIE_SECURE:true}}") boolean cookieSecure,
            @Value("${app.jwt.allow-generated-secret:false}") boolean allowGeneratedSecret,
            @Value("${app.jwt.require-redis-token-store:false}") boolean requireRedisTokenStore,
            ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        return new JwtTokenService(
                rawSecret,
                accessTokenTtlSeconds,
                refreshTokenTtlSeconds,
                accessCookieMaxAgeSeconds,
                refreshCookieMaxAgeSeconds,
                cookieSecure,
                allowGeneratedSecret,
                requireRedisTokenStore,
                redisTemplateProvider.getIfAvailable());
    }

    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(
            SessionTokenService tokenService, SessionTokenTransport tokenTransport, UserAccountStore userAccountStore) {
        return new JwtAuthenticationFilter(tokenService, tokenTransport, userAccountStore);
    }

    @Bean
    public SecurityFilterChain filterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            ApiRateLimitFilter apiRateLimitFilter,
            SessionTokenTransport tokenTransport,
            AuditService auditService,
            ObjectMapper objectMapper)
            throws Exception {
        http.csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()))
                .addFilterBefore(jwtAuthenticationFilter, LogoutFilter.class)
                .addFilterAfter(new UserMdcFilter(), JwtAuthenticationFilter.class)
                .addFilterAfter(apiRateLimitFilter, UserMdcFilter.class)
                .addFilterAfter(new PasswordChangeRequiredFilter(objectMapper), JwtAuthenticationFilter.class)
                .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth.requestMatchers(
                                "/",
                                "/login",
                                "/shop",
                                "/admin",
                                "/orders",
                                "/profile",
                                "/index.html",
                                "/shop.html",
                                "/admin.html",
                                "/orders.html",
                                "/profile.html",
                                "/favicon.ico",
                                "/icons.svg",
                                "/robots.txt",
                                "/sitemap.xml",
                                "/assets/**",
                                "/css/**",
                                "/js/**",
                                "/images/**",
                                "/api/v1/openapi",
                                "/api/v1/openapi/**",
                                "/api/v1/docs",
                                "/api/v1/docs/**",
                                "/swagger-ui/**",
                                "/v3/api-docs/**")
                        .permitAll()
                        .requestMatchers(
                                "/api/auth/captcha",
                                "/api/v1/auth/captcha",
                                "/api/auth/captcha/config",
                                "/api/v1/auth/captcha/config",
                                "/api/auth/register",
                                "/api/v1/auth/register",
                                "/api/auth/login",
                                "/api/v1/auth/login",
                                "/api/auth/refresh",
                                "/api/v1/auth/refresh",
                                "/api/auth/reset-password",
                                "/api/v1/auth/reset-password",
                                "/api/auth/reset-password/request",
                                "/api/v1/auth/reset-password/request")
                        .permitAll()
                        .requestMatchers(
                                HttpMethod.GET, "/api/monkeys", "/api/v1/monkeys", "/api/user/me", "/api/v1/users/me")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/upload/avatar", "/api/v1/uploads/avatar")
                        .hasAuthority("UPLOAD_AVATAR")
                        .requestMatchers(HttpMethod.POST, "/api/upload/product", "/api/v1/uploads/product")
                        .hasAuthority("UPLOAD_PRODUCT_IMAGE")
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/upload",
                                "/api/upload/**",
                                "/api/v1/uploads",
                                "/api/v1/uploads/**")
                        .hasAnyAuthority("UPLOAD_AVATAR", "UPLOAD_PRODUCT_IMAGE")
                        .requestMatchers(HttpMethod.GET, "/api/upload/presigned-get", "/api/v1/uploads/presigned-get")
                        .hasAnyAuthority("UPLOAD_AVATAR", "UPLOAD_PRODUCT_IMAGE")
                        .requestMatchers("/api/address", "/api/address/**", "/api/v1/addresses", "/api/v1/addresses/**")
                        .hasAuthority("ADDRESS_MANAGE")
                        .requestMatchers("/api/user/profile", "/api/v1/users/profile")
                        .hasAuthority("USER_PROFILE_READ")
                        .requestMatchers(
                                "/api/user/captcha",
                                "/api/v1/users/captcha",
                                "/api/user/update-avatar",
                                "/api/v1/users/update-avatar",
                                "/api/user/update-password",
                                "/api/v1/users/update-password",
                                "/api/user/forget-me",
                                "/api/v1/users/forget-me")
                        .hasAuthority("USER_PROFILE_WRITE")
                        .requestMatchers("/api/user/logout", "/api/v1/users/logout")
                        .authenticated()
                        .requestMatchers("/api/orders/create", "/api/v1/orders/create")
                        .hasAuthority("ORDER_CREATE")
                        .requestMatchers(
                                "/api/orders/my",
                                "/api/v1/orders/my",
                                "/api/orders/receive/**",
                                "/api/v1/orders/receive/**")
                        .hasAuthority("ORDER_READ_OWN")
                        .requestMatchers(HttpMethod.DELETE, "/api/orders/**", "/api/v1/orders/**")
                        .hasAuthority("ORDER_READ_OWN")
                        .requestMatchers(
                                "/api/orders/return/apply/**",
                                "/api/v1/orders/return/apply/**",
                                "/api/orders/return/ship/**",
                                "/api/v1/orders/return/ship/**")
                        .hasAuthority("ORDER_RETURN_REQUEST")
                        .requestMatchers(
                                "/api/monkeys/add",
                                "/api/v1/monkeys/add",
                                "/api/monkeys/update",
                                "/api/v1/monkeys/update")
                        .hasAuthority("PRODUCT_MANAGE")
                        .requestMatchers("/api/stats/**", "/api/v1/stats/**")
                        .hasAuthority("ADMIN_DASHBOARD_READ")
                        .requestMatchers(
                                "/api/orders/all",
                                "/api/v1/orders/all",
                                "/api/orders/ship/**",
                                "/api/v1/orders/ship/**",
                                "/api/orders/return/approve/**",
                                "/api/v1/orders/return/approve/**",
                                "/api/orders/return/confirm/**",
                                "/api/v1/orders/return/confirm/**")
                        .hasAuthority("ORDER_MANAGE")
                        .requestMatchers(HttpMethod.DELETE, "/api/monkeys/**", "/api/v1/monkeys/**")
                        .hasAuthority("PRODUCT_MANAGE")
                        .requestMatchers("/api/**")
                        .denyAll()
                        .anyRequest()
                        .denyAll())
                .headers(headers -> headers.addHeaderWriter(new NonceContentSecurityPolicyHeaderWriter())
                        .httpStrictTransportSecurity(hsts ->
                                hsts.includeSubDomains(true).preload(true).maxAgeInSeconds(31536000))
                        .frameOptions(frame -> frame.deny())
                        .referrerPolicy(referrer -> referrer.policy(
                                ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        .addHeaderWriter(new StaticHeadersWriter(
                                "Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()")))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .maximumSessions(1))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) ->
                                writeProblem(response, objectMapper, ErrorCode.UNAUTHORIZED, request))
                        .accessDeniedHandler((request, response, exception) -> {
                            ErrorCode errorCode = accessDeniedCode(exception);
                            writeProblem(response, objectMapper, errorCode, request);
                        }))
                .logout(logout -> logout.logoutRequestMatcher(SecurityConfig::isLogoutRequest)
                        .addLogoutHandler(
                                (request, response, authentication) -> tokenTransport.revokeTokens(request, response))
                        .logoutSuccessHandler((request, response, authentication) -> {
                            if (!isAuthenticatedPrincipal(authentication)) {
                                auditLogoutFailure(auditService, request);
                                writeProblem(response, objectMapper, ErrorCode.UNAUTHORIZED, request);
                                return;
                            }
                            auditLogoutSuccess(auditService, authentication, request);
                            writeJson(response, objectMapper, HttpServletResponse.SC_OK, Result.success());
                        }));
        return http.build();
    }

    private static void writeProblem(
            HttpServletResponse response, ObjectMapper objectMapper, ErrorCode errorCode, HttpServletRequest request)
            throws IOException {
        writeProblem(response, objectMapper, errorCode, errorCode.defaultMessage(), request);
    }

    private static void writeProblem(
            HttpServletResponse response,
            ObjectMapper objectMapper,
            ErrorCode errorCode,
            String detail,
            HttpServletRequest request)
            throws IOException {
        var problem = ProblemDetails.from(errorCode, detail, request);
        writeJson(
                response,
                objectMapper,
                ErrorHttpStatuses.forCode(errorCode).value(),
                problem,
                MediaType.APPLICATION_PROBLEM_JSON);
    }

    private static ErrorCode accessDeniedCode(Exception exception) {
        if (exception instanceof CsrfException) {
            return ErrorCode.FORBIDDEN;
        }
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || TRUST_RESOLVER.isAnonymous(authentication)) {
            return ErrorCode.UNAUTHORIZED;
        }
        return ErrorCode.FORBIDDEN;
    }

    private static boolean isAuthenticatedPrincipal(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !TRUST_RESOLVER.isAnonymous(authentication);
    }

    private static void auditLogoutSuccess(
            AuditService auditService, Authentication authentication, HttpServletRequest request) {
        SessionUser user = sessionUser(authentication);
        auditService.record(
                AuditService.LOGOUT_SUCCESS,
                AuditService.OUTCOME_SUCCESS,
                user == null ? null : user.id(),
                user == null ? null : user.role(),
                null,
                ClientIps.resolve(request),
                null);
    }

    private static void auditLogoutFailure(AuditService auditService, HttpServletRequest request) {
        auditService.record(
                AuditService.LOGOUT_FAILURE,
                AuditService.OUTCOME_FAILURE,
                null,
                null,
                null,
                ClientIps.resolve(request),
                "missing_authentication");
    }

    private static SessionUser sessionUser(Authentication authentication) {
        if (authentication == null) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        return principal instanceof SessionUser user ? user : null;
    }

    private static boolean isPasswordChangeAllowedPath(HttpServletRequest request) {
        String path = ApiPaths.canonicalize(request.getRequestURI());
        String method = request.getMethod();
        return path == null
                || !path.startsWith("/api/")
                || path.startsWith("/api/auth/")
                || (HttpMethod.GET.matches(method) && "/api/user/me".equals(path))
                || (HttpMethod.GET.matches(method) && "/api/user/profile".equals(path))
                || (HttpMethod.GET.matches(method) && "/api/user/captcha".equals(path))
                || (HttpMethod.POST.matches(method) && "/api/user/update-password".equals(path))
                || (HttpMethod.POST.matches(method) && "/api/user/logout".equals(path));
    }

    private static boolean isLogoutRequest(HttpServletRequest request) {
        String path = ApiPaths.canonicalize(request.getRequestURI());
        return HttpMethod.POST.matches(request.getMethod()) && "/api/user/logout".equals(path);
    }

    private static User toUserDetails(UserAccount account) {
        return new User(
                account.username(),
                account.passwordHash(),
                true,
                true,
                credentialsAreCurrent(account),
                true,
                account.authorityNames().stream()
                        .map(SimpleGrantedAuthority::new)
                        .toList());
    }

    private static boolean credentialsAreCurrent(UserAccount account) {
        return account.passwordLastChangedAt() == null
                || !account.passwordLastChangedAt().isBefore(LocalDateTime.now().minusDays(90));
    }

    private static void writeJson(HttpServletResponse response, ObjectMapper objectMapper, int status, Object body)
            throws IOException {
        writeJson(response, objectMapper, status, body, MediaType.APPLICATION_JSON);
    }

    private static void writeJson(
            HttpServletResponse response, ObjectMapper objectMapper, int status, Object body, MediaType mediaType)
            throws IOException {
        response.setStatus(status);
        response.setContentType(mediaType.toString());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), body);
    }

    private static final class NonceContentSecurityPolicyHeaderWriter implements HeaderWriter {

        private static final SecureRandom SECURE_RANDOM = new SecureRandom();
        private static final Base64.Encoder BASE64_ENCODER = Base64.getEncoder().withoutPadding();
        private static final int NONCE_BYTES = 16;

        @Override
        public void writeHeaders(
                jakarta.servlet.http.HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response) {
            String nonce = newNonce();
            request.setAttribute(CSP_NONCE_ATTRIBUTE, nonce);
            response.setHeader("Content-Security-Policy", policy(nonce));
        }

        private static String newNonce() {
            byte[] nonceBytes = new byte[NONCE_BYTES];
            SECURE_RANDOM.nextBytes(nonceBytes);
            return BASE64_ENCODER.encodeToString(nonceBytes);
        }

        private static String policy(String nonce) {
            return "default-src 'self'; "
                    + "script-src 'self' 'nonce-"
                    + nonce
                    + "' https://challenges.cloudflare.com; "
                    + "style-src 'self' 'nonce-" + nonce + "'; "
                    + "img-src 'self' data:; "
                    + "font-src 'self' data:; "
                    + "connect-src 'self' https://challenges.cloudflare.com; "
                    + "frame-src https://challenges.cloudflare.com; "
                    + "object-src 'none'; "
                    + "base-uri 'self'; "
                    + "form-action 'self'; "
                    + "frame-ancestors 'none'; "
                    + "upgrade-insecure-requests";
        }
    }

    private static final class PasswordChangeRequiredFilter extends OncePerRequestFilter {

        private final ObjectMapper objectMapper;

        private PasswordChangeRequiredFilter(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        @Override
        protected void doFilterInternal(
                HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
                throws ServletException, IOException {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            SessionUser user = sessionUser(authentication);
            if (isAuthenticatedPrincipal(authentication)
                    && user != null
                    && user.passwordChangeRequired()
                    && !isPasswordChangeAllowedPath(request)) {
                writeProblem(response, objectMapper, ErrorCode.FORBIDDEN, PASSWORD_CHANGE_REQUIRED, request);
                return;
            }
            filterChain.doFilter(request, response);
        }
    }
}
