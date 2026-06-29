package com.example.monkey.security;

import com.example.monkey.domain.user.SessionTokenService;
import com.example.monkey.domain.user.SessionUser;
import com.example.monkey.domain.user.UserAccountStore;
import com.example.monkey.domain.user.UserAccountStore.UserAccount;
import com.example.monkey.shared.web.SessionTokenTransport;
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
                    .flatMap(token -> currentAuthenticatedUser(token.userId()))
                    .ifPresent(currentUser -> {
                        SessionUser user = new SessionUser(
                                currentUser.id(), currentUser.role(), currentUser.passwordChangeRequired());
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

    private Optional<UserAccount> currentAuthenticatedUser(Long userId) {
        return userAccountStore.findById(userId).filter(JwtAuthenticationFilter::credentialsAreCurrent);
    }

    private static boolean credentialsAreCurrent(UserAccount user) {
        return user.passwordLastChangedAt() == null
                || !user.passwordLastChangedAt().isBefore(LocalDateTime.now().minusDays(90));
    }
}
