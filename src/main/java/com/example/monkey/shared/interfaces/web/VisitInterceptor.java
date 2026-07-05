package com.example.monkey.shared.interfaces.web;

import com.example.monkey.shared.application.observability.VisitMetricsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class VisitInterceptor implements HandlerInterceptor {

    private final VisitMetricsService visitMetricsService;

    public VisitInterceptor(VisitMetricsService visitMetricsService) {
        this.visitMetricsService = visitMetricsService;
    }

    @Override
    public boolean preHandle(
            @NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull Object handler) {
        visitMetricsService.recordPageVisit(request.getMethod(), request.getRequestURI(), ClientIps.resolve(request));
        return true;
    }
}
