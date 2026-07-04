package com.example.monkey.user.infrastructure;

import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.application.tenant.TenantContext;
import com.example.monkey.shared.interfaces.web.SessionTokenTransport;
import com.example.monkey.user.domain.SessionTokenService;
import com.example.monkey.user.domain.SessionTokenService.AuthenticatedAccessToken;
import com.example.monkey.user.domain.UserAccountStore;
import com.example.monkey.user.domain.UserAccountStore.UserAccount;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final SessionTokenService tokenService;
    private final SessionTokenTransport tokenTransport;
    private final UserAccountStore userAccountStore;

    public JwtAuthenticationFilter(
            SessionTokenService tokenService, SessionTokenTransport tokenTransport, UserAccountStore userAccountStore) {
        this.tokenService = tokenService;
        this.tokenTransport = tokenTransport;
        this.userAccountStore = userAccountStore;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Authentication existing = SecurityContextHolder.getContext().getAuthentication();
        if (existing == null || !existing.isAuthenticated()) {
            tokenTransport
                    .resolveAccessToken(request)
                    .flatMap(tokenService::parseAccessToken)
                    .flatMap(token -> currentAuthenticatedUser(token).map(currentUser -> session(token, currentUser)))
                    .ifPresent(session -> {
                        UserAccount currentUser = session.user();
                        SessionUser user = new SessionUser(
                                currentUser.id(),
                                currentUser.role(),
                                passwordChangeRequired(currentUser),
                                session.token().tenantId());
                        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                                user,
                                null,
                                currentUser.authorityNames().stream()
                                        .map(SimpleGrantedAuthority::new)
                                        .toList());
                        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                        SecurityContextHolder.getContext().setAuthentication(auth);
                    });
        }
        filterChain.doFilter(request, response);
    }

    private Optional<UserAccount> currentAuthenticatedUser(AuthenticatedAccessToken token) {
        Optional<Long> previousTenant = TenantContext.currentTenantId();
        TenantContext.setTenantId(token.tenantId());
        try {
            return userAccountStore.findById(token.userId());
        } finally {
            previousTenant.ifPresentOrElse(TenantContext::setTenantId, TenantContext::clear);
        }
    }

    private static AuthenticatedSession session(AuthenticatedAccessToken token, UserAccount user) {
        return new AuthenticatedSession(token, user);
    }

    private static boolean passwordChangeRequired(UserAccount user) {
        return user.passwordChangeRequired() || passwordExpired(user.passwordLastChangedAt());
    }

    private static boolean passwordExpired(LocalDateTime passwordLastChangedAt) {
        return passwordLastChangedAt != null
                && passwordLastChangedAt.isBefore(LocalDateTime.now().minusDays(90));
    }

    private record AuthenticatedSession(AuthenticatedAccessToken token, UserAccount user) {}
}
