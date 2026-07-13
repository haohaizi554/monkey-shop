package com.example.monkey.shared.interfaces.web;

import com.example.monkey.shared.domain.security.TrustedProxyPolicy;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ClientIpResolutionFilter extends OncePerRequestFilter {

    private final TrustedProxyPolicy trustedProxyPolicy;

    public ClientIpResolutionFilter(TrustedProxyPolicy trustedProxyPolicy) {
        this.trustedProxyPolicy = trustedProxyPolicy;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain)
            throws ServletException, IOException {
        ClientIps.setResolved(request, ClientIps.resolve(request, trustedProxyPolicy));
        filterChain.doFilter(request, response);
    }
}
