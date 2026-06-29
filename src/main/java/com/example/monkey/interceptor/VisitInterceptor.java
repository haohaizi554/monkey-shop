package com.example.monkey.interceptor;

import com.example.monkey.service.VisitMetricsService;
import com.example.monkey.shared.web.ClientIps;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class VisitInterceptor implements HandlerInterceptor {

    private final VisitMetricsService visitMetricsService;

    public VisitInterceptor(VisitMetricsService visitMetricsService) {
        this.visitMetricsService = visitMetricsService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        visitMetricsService.recordPageVisit(request.getMethod(), request.getRequestURI(), ClientIps.resolve(request));
        return true;
    }
}
