package com.example.monkey.shared.interfaces.security;

import com.example.monkey.shared.application.security.ApiRateLimitApplicationService;
import com.example.monkey.shared.application.security.ApiRateLimitOperation;
import com.example.monkey.shared.application.security.ApiRateLimitResult;
import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.shared.interfaces.web.ApiPaths;
import com.example.monkey.shared.interfaces.web.ClientIps;
import com.example.monkey.shared.interfaces.web.ErrorHttpStatuses;
import com.example.monkey.shared.interfaces.web.ProblemDetails;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class ApiRateLimitFilter extends OncePerRequestFilter {

    private static final String RETRY_AFTER = "Retry-After";

    private final ApiRateLimitApplicationService rateLimitService;
    private final ObjectMapper objectMapper;

    public ApiRateLimitFilter(ApiRateLimitApplicationService rateLimitService, ObjectMapper objectMapper) {
        this.rateLimitService = rateLimitService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return !isApiRequest(request) && !isHoneypot(request);
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain)
            throws ServletException, IOException {
        String clientIp = ClientIps.resolve(request);
        if (rateLimitService.isBlocked(clientIp)) {
            writeProblem(response, request, ErrorCode.FORBIDDEN, ErrorCode.FORBIDDEN.defaultMessage(), null);
            return;
        }
        if (isHoneypot(request)) {
            rateLimitService.blockForHoneypot(clientIp);
            writeProblem(response, request, ErrorCode.FORBIDDEN, ErrorCode.FORBIDDEN.defaultMessage(), null);
            return;
        }

        ApiRateLimitOperation operation = operationFor(request);
        ApiRateLimitResult decision = rateLimitService.consume(operation, clientIp, authenticatedUserKey());
        if (!decision.allowed()) {
            writeProblem(
                    response,
                    request,
                    ErrorCode.RATE_LIMIT,
                    ErrorCode.RATE_LIMIT.defaultMessage(),
                    decision.retryAfterSeconds());
            return;
        }
        filterChain.doFilter(request, response);
    }

    private void writeProblem(
            HttpServletResponse response,
            HttpServletRequest request,
            ErrorCode errorCode,
            String detail,
            Long retryAfterSeconds)
            throws IOException {
        if (retryAfterSeconds != null) {
            response.setHeader(RETRY_AFTER, Long.toString(Math.max(1L, retryAfterSeconds)));
        }
        response.setStatus(ErrorHttpStatuses.forCode(errorCode).value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), ProblemDetails.from(errorCode, detail, request));
    }

    private static boolean isApiRequest(HttpServletRequest request) {
        String path = request.getRequestURI();
        return ApiPaths.isApiRequest(path) && !HttpMethod.OPTIONS.matches(request.getMethod());
    }

    private static boolean isHoneypot(HttpServletRequest request) {
        String path = ApiPaths.canonicalize(request.getRequestURI());
        return "/api/.env".equals(path)
                || "/admin/secret".equals(path)
                || "/api/seckill/internal/active".equals(path)
                || "/api/search/internal/hot".equals(path)
                || "/api/risk/internal/probe".equals(path)
                || "/api/tracking/internal/pixel".equals(path)
                || "/api/tenants/internal/probe".equals(path);
    }

    private static ApiRateLimitOperation operationFor(HttpServletRequest request) {
        String method = request.getMethod();
        String path = ApiPaths.canonicalize(request.getRequestURI());
        if (HttpMethod.POST.matches(method) && "/api/auth/login".equals(path)) {
            return ApiRateLimitOperation.LOGIN;
        }
        if (HttpMethod.POST.matches(method) && "/api/auth/register".equals(path)) {
            return ApiRateLimitOperation.REGISTER;
        }
        if (HttpMethod.POST.matches(method) && "/api/orders/create".equals(path)) {
            return ApiRateLimitOperation.ORDER;
        }
        if (HttpMethod.POST.matches(method)
                && ("/api/marketing/seckill-orders".equals(path) || "/api/v1/marketing/seckill-orders".equals(path))) {
            return ApiRateLimitOperation.SECKILL;
        }
        if ((HttpMethod.POST.matches(method) || HttpMethod.PATCH.matches(method) || HttpMethod.DELETE.matches(method))
                && isCartPath(path)) {
            return ApiRateLimitOperation.CART;
        }
        if (HttpMethod.POST.matches(method) && isPaymentPath(path)) {
            return ApiRateLimitOperation.PAYMENT;
        }
        if (isLogisticsPath(path)) {
            return ApiRateLimitOperation.LOGISTICS;
        }
        if (isMembershipPath(path)) {
            return ApiRateLimitOperation.MEMBERSHIP;
        }
        if (isSearchPath(path)) {
            return ApiRateLimitOperation.SEARCH;
        }
        if (isRiskPath(path)) {
            return ApiRateLimitOperation.RISK;
        }
        if (isTrackingPath(path)) {
            return ApiRateLimitOperation.TRACKING;
        }
        if (isTenantPath(path)) {
            return ApiRateLimitOperation.TENANT;
        }
        if (HttpMethod.GET.matches(method)
                && ("/api/monkeys".equals(path)
                        || (path != null
                                && (path.startsWith("/api/catalog")
                                        || path.startsWith("/api/v1/catalog")
                                        || path.startsWith("/api/inventory")
                                        || path.startsWith("/api/v1/inventory"))))) {
            return ApiRateLimitOperation.SEARCH;
        }
        if (HttpMethod.POST.matches(method) && path != null && path.startsWith("/api/upload")) {
            return ApiRateLimitOperation.UPLOAD;
        }
        return ApiRateLimitOperation.DEFAULT;
    }

    private static boolean isCartPath(String path) {
        return "/api/cart".equals(path) || (path != null && path.startsWith("/api/cart/"));
    }

    private static boolean isPaymentPath(String path) {
        return path != null
                && (path.startsWith("/api/payments/pay")
                        || path.startsWith("/api/v1/payments/pay")
                        || path.startsWith("/api/payments/refund")
                        || path.startsWith("/api/v1/payments/refund"));
    }

    private static boolean isLogisticsPath(String path) {
        return path != null && (path.startsWith("/api/logistics") || path.startsWith("/api/v1/logistics"));
    }

    private static boolean isMembershipPath(String path) {
        return path != null && (path.startsWith("/api/membership") || path.startsWith("/api/v1/membership"));
    }

    private static boolean isSearchPath(String path) {
        return path != null && (path.startsWith("/api/search") || path.startsWith("/api/v1/search"));
    }

    private static boolean isRiskPath(String path) {
        return path != null && (path.startsWith("/api/risk") || path.startsWith("/api/v1/risk"));
    }

    private static boolean isTrackingPath(String path) {
        return path != null && (path.startsWith("/api/tracking") || path.startsWith("/api/v1/tracking"));
    }

    private static boolean isTenantPath(String path) {
        return path != null && (path.startsWith("/api/tenants") || path.startsWith("/api/v1/tenants"));
    }

    private static String authenticatedUserKey() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return "anonymous";
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof SessionUser user && user.id() != null) {
            return "tenant:" + user.tenantId() + ":user:" + user.id();
        }
        return authentication.getName() == null
                ? "anonymous"
                : "principal:" + authentication.getName().toLowerCase(Locale.ROOT);
    }
}
