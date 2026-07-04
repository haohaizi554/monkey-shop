package com.example.monkey.user.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.application.tenant.TenantContext;
import com.example.monkey.shared.domain.security.JwtTokenPair;
import com.example.monkey.user.domain.UserAccountStore;
import com.example.monkey.user.domain.UserAccountStore.UserAccount;
import jakarta.servlet.FilterChain;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

class JwtAuthenticationFilterTest {

    private static final String TEST_SECRET = "unit-test-secret-key-should-be-long-enough-for-hmac";

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void restoresCurrentPermissionAuthoritiesForTokenSubject() throws Exception {
        JwtTokenService tokenService = new JwtTokenService(TEST_SECRET, 30, 60, 30, 60, false, null);
        JwtTokenPair tokenPair = tokenService.issueTokenPair(7L, "USER", List.of("ROLE_USER", "ORDER_CREATE"), 200L);
        UserAccountStore userAccountStore = mock(UserAccountStore.class);
        when(userAccountStore.findById(7L)).thenAnswer(invocation -> {
            assertThat(TenantContext.currentTenantIdOrDefault()).isEqualTo(200L);
            return Optional.of(account(7L, false, List.of("ROLE_USER", "ORDER_READ_OWN")));
        });
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(tokenService, tokenService, userAccountStore);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + tokenPair.accessToken());
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Authentication> authentication = new AtomicReference<>();
        FilterChain chain = (servletRequest, servletResponse) ->
                authentication.set(SecurityContextHolder.getContext().getAuthentication());

        filter.doFilter(request, response, chain);

        assertThat(authentication.get()).isNotNull();
        assertThat(authentication.get().getPrincipal()).isEqualTo(new SessionUser(7L, "USER", false, 200L));
        assertThat(authentication.get().getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_USER", "ORDER_READ_OWN");
    }

    @Test
    void restoresPasswordChangeRequirementForTokenSubject() throws Exception {
        JwtTokenService tokenService = new JwtTokenService(TEST_SECRET, 30, 60, 30, 60, false, null);
        JwtTokenPair tokenPair = tokenService.issueTokenPair(7L, "USER", List.of("ROLE_USER"));
        UserAccountStore userAccountStore = mock(UserAccountStore.class);
        when(userAccountStore.findById(7L))
                .thenReturn(Optional.of(account(7L, true, List.of("ROLE_USER", "ORDER_READ_OWN"))));
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(tokenService, tokenService, userAccountStore);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + tokenPair.accessToken());
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Authentication> authentication = new AtomicReference<>();
        FilterChain chain = (servletRequest, servletResponse) ->
                authentication.set(SecurityContextHolder.getContext().getAuthentication());

        filter.doFilter(request, response, chain);

        assertThat(authentication.get().getPrincipal()).isEqualTo(new SessionUser(7L, "USER", true));
    }

    @Test
    void restoresExpiredCredentialsAsPasswordChangeRequiredPrincipal() throws Exception {
        JwtTokenService tokenService = new JwtTokenService(TEST_SECRET, 30, 60, 30, 60, false, null);
        JwtTokenPair tokenPair = tokenService.issueTokenPair(7L, "USER", List.of("ROLE_USER"));
        UserAccountStore userAccountStore = mock(UserAccountStore.class);
        when(userAccountStore.findById(7L))
                .thenReturn(Optional.of(account(
                        7L,
                        false,
                        List.of("ROLE_USER", "ORDER_READ_OWN"),
                        LocalDateTime.now().minusDays(91))));
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(tokenService, tokenService, userAccountStore);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + tokenPair.accessToken());
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Authentication> authentication = new AtomicReference<>();
        FilterChain chain = (servletRequest, servletResponse) ->
                authentication.set(SecurityContextHolder.getContext().getAuthentication());

        filter.doFilter(request, response, chain);

        assertThat(authentication.get()).isNotNull();
        assertThat(authentication.get().getPrincipal()).isEqualTo(new SessionUser(7L, "USER", true));
        assertThat(authentication.get().getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_USER", "ORDER_READ_OWN");
    }

    @Test
    void doesNotAuthenticateJwtWhenCurrentUserIsMissing() throws Exception {
        JwtTokenService tokenService = new JwtTokenService(TEST_SECRET, 30, 60, 30, 60, false, null);
        JwtTokenPair tokenPair = tokenService.issueTokenPair(7L, "USER", List.of("ROLE_USER"));
        UserAccountStore userAccountStore = mock(UserAccountStore.class);
        when(userAccountStore.findById(7L)).thenReturn(Optional.empty());
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(tokenService, tokenService, userAccountStore);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + tokenPair.accessToken());
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Authentication> authentication = new AtomicReference<>();
        FilterChain chain = (servletRequest, servletResponse) ->
                authentication.set(SecurityContextHolder.getContext().getAuthentication());

        filter.doFilter(request, response, chain);

        assertThat(authentication.get()).isNull();
    }

    private static UserAccount account(Long userId, boolean passwordChangeRequired, List<String> authorityNames) {
        return account(
                userId,
                passwordChangeRequired,
                authorityNames,
                LocalDateTime.now().minusDays(10));
    }

    private static UserAccount account(
            Long userId,
            boolean passwordChangeRequired,
            List<String> authorityNames,
            LocalDateTime passwordLastChangedAt) {
        return new UserAccount(
                userId,
                "alice",
                "encoded-password",
                "18888888888",
                "alice@example.com",
                "/avatar.png",
                "USER",
                null,
                passwordLastChangedAt,
                passwordChangeRequired,
                null,
                false,
                authorityNames);
    }
}
