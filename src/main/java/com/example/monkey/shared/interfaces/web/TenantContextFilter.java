package com.example.monkey.shared.interfaces.web;

import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.application.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

public class TenantContextFilter extends OncePerRequestFilter {

    public static final String TENANT_HEADER = "X-Tenant-Id";
    public static final String TENANT_ATTRIBUTE = "tenant_id";

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain)
            throws ServletException, IOException {
        Long tenantId = resolveTenantId(request).orElse(SessionUser.DEFAULT_TENANT_ID);
        TenantContext.setTenantId(tenantId);
        request.setAttribute(TENANT_ATTRIBUTE, TenantContext.currentTenantIdOrDefault());
        try {
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private static Optional<Long> resolveTenantId(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        SessionUser user = authentication == null || !(authentication.getPrincipal() instanceof SessionUser sessionUser)
                ? null
                : sessionUser;
        if (user != null && !"ADMIN".equals(user.role())) {
            return Optional.of(user.tenantId());
        }
        return parseTenantHeader(request).or(() -> user == null ? Optional.empty() : Optional.of(user.tenantId()));
    }

    private static Optional<Long> parseTenantHeader(HttpServletRequest request) {
        String rawTenantId = request.getHeader(TENANT_HEADER);
        if (!StringUtils.hasText(rawTenantId)) {
            return Optional.empty();
        }
        try {
            long tenantId = Long.parseLong(rawTenantId.trim());
            return tenantId > 0 ? Optional.of(tenantId) : Optional.empty();
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }
}
