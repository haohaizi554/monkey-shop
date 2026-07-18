package com.example.monkey.shared.interfaces.web;

import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.application.tenant.TenantAccessGateway;
import com.example.monkey.shared.application.tenant.TenantContext;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

public class TenantContextFilter extends OncePerRequestFilter {

    public static final String TENANT_HEADER = "X-Tenant-Id";
    public static final String TENANT_ATTRIBUTE = "tenant_id";

    private final TenantAccessGateway tenantAccessGateway;
    private final ObjectMapper objectMapper;

    public TenantContextFilter(TenantAccessGateway tenantAccessGateway, ObjectMapper objectMapper) {
        this.tenantAccessGateway = tenantAccessGateway;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return !ApiPaths.isApiRequest(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain)
            throws ServletException, IOException {
        try {
            String rawTenantId = request.getHeader(TENANT_HEADER);
            Long requestedTenantId = null;
            if (rawTenantId != null) {
                requestedTenantId = parseTenantHeader(rawTenantId);
                if (requestedTenantId == null) {
                    writeProblem(response, request, ErrorCode.REQUEST_MALFORMED);
                    return;
                }
            }

            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            SessionUser user = sessionUser(authentication);
            if (isCrossTenantSelection(user, requestedTenantId) && !canSelectAnotherTenant(authentication, user)) {
                writeProblem(response, request, ErrorCode.FORBIDDEN);
                return;
            }

            Long tenantId = resolveTenantId(user, requestedTenantId);
            if (!tenantAccessGateway.isServiceableTenant(tenantId)) {
                writeProblem(response, request, ErrorCode.FORBIDDEN);
                return;
            }

            TenantContext.setTenantId(tenantId);
            request.setAttribute(TENANT_ATTRIBUTE, tenantId);
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private static SessionUser sessionUser(Authentication authentication) {
        return authentication == null || !(authentication.getPrincipal() instanceof SessionUser sessionUser)
                ? null
                : sessionUser;
    }

    private static boolean isCrossTenantSelection(SessionUser user, Long requestedTenantId) {
        return user != null && requestedTenantId != null && !user.tenantId().equals(requestedTenantId);
    }

    private static boolean canSelectAnotherTenant(Authentication authentication, SessionUser user) {
        if (authentication == null || user == null || !"ADMIN".equals(user.role())) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> "TENANT_READ".equals(authority.getAuthority())
                        || "TENANT_ADMIN".equals(authority.getAuthority()));
    }

    private static Long resolveTenantId(SessionUser user, Long requestedTenantId) {
        if (user != null && !"ADMIN".equals(user.role())) {
            return user.tenantId();
        }
        if (requestedTenantId != null) {
            return requestedTenantId;
        }
        return user == null ? SessionUser.DEFAULT_TENANT_ID : user.tenantId();
    }

    private static Long parseTenantHeader(String rawTenantId) {
        if (!StringUtils.hasText(rawTenantId)) {
            return null;
        }
        try {
            long tenantId = Long.parseLong(rawTenantId.trim());
            return tenantId > 0 ? tenantId : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private void writeProblem(HttpServletResponse response, HttpServletRequest request, ErrorCode errorCode)
            throws IOException {
        response.setStatus(ErrorHttpStatuses.forCode(errorCode).value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(
                response.getWriter(), ProblemDetails.from(errorCode, errorCode.defaultMessage(), request));
    }
}
