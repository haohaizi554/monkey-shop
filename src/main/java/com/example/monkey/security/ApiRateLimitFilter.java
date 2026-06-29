package com.example.monkey.security;

import com.example.monkey.domain.security.ApiRateLimiter;
import com.example.monkey.domain.security.ApiRateLimiter.RateLimitDecision;
import com.example.monkey.domain.security.RateLimitPolicy;
import com.example.monkey.domain.user.SessionUser;
import com.example.monkey.shared.api.ErrorCode;
import com.example.monkey.shared.web.ApiPaths;
import com.example.monkey.shared.web.ClientIps;
import com.example.monkey.shared.web.ProblemDetails;
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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class ApiRateLimitFilter extends OncePerRequestFilter {

    private static final String RETRY_AFTER = "Retry-After";

    private final ApiRateLimiter rateLimitService;
    private final ObjectMapper objectMapper;

    public ApiRateLimitFilter(ApiRateLimiter rateLimitService, ObjectMapper objectMapper) {
        this.rateLimitService = rateLimitService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !isApiRequest(request) && !isHoneypot(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
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

        RateLimitPolicy policy = policyFor(request);
        RateLimitDecision decision = rateLimitService.consume(policy, clientIp, authenticatedUserKey());
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
        response.setStatus(errorCode.httpStatus().value());
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
        return "/api/.env".equals(path) || "/admin/secret".equals(path);
    }

    private static RateLimitPolicy policyFor(HttpServletRequest request) {
        String method = request.getMethod();
        String path = ApiPaths.canonicalize(request.getRequestURI());
        if (HttpMethod.POST.matches(method) && "/api/auth/login".equals(path)) {
            return RateLimitPolicy.LOGIN;
        }
        if (HttpMethod.POST.matches(method) && "/api/auth/register".equals(path)) {
            return RateLimitPolicy.REGISTER;
        }
        if (HttpMethod.POST.matches(method) && "/api/orders/create".equals(path)) {
            return RateLimitPolicy.ORDER;
        }
        if (HttpMethod.GET.matches(method) && "/api/monkeys".equals(path)) {
            return RateLimitPolicy.SEARCH;
        }
        if (HttpMethod.POST.matches(method) && path != null && path.startsWith("/api/upload")) {
            return RateLimitPolicy.UPLOAD;
        }
        return RateLimitPolicy.DEFAULT;
    }

    private static String authenticatedUserKey() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return "anonymous";
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof SessionUser user && user.id() != null) {
            return "user:" + user.id();
        }
        return authentication.getName() == null
                ? "anonymous"
                : "principal:" + authentication.getName().toLowerCase(Locale.ROOT);
    }
}
