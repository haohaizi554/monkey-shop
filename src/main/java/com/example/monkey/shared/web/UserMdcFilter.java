package com.example.monkey.shared.web;

import com.example.monkey.domain.user.SessionUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class UserMdcFilter extends OncePerRequestFilter {

    private static final AuthenticationTrustResolver TRUST_RESOLVER = new AuthenticationTrustResolverImpl();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        SessionUser user = sessionUser(authentication);
        if (user != null && user.id() != null) {
            MDC.put(TraceIds.USER_ID_MDC_KEY, user.id().toString());
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(TraceIds.USER_ID_MDC_KEY);
        }
    }

    private static SessionUser sessionUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || TRUST_RESOLVER.isAnonymous(authentication)) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        return principal instanceof SessionUser user ? user : null;
    }
}
