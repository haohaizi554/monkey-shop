package com.example.monkey.service;

import com.example.monkey.domain.observability.VisitLogRecorder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class VisitMetricsService {

    private final MeterRegistry meterRegistry;
    private final VisitLogRecorder visitLogRecorder;

    public VisitMetricsService(MeterRegistry meterRegistry, VisitLogRecorder visitLogRecorder) {
        this.meterRegistry = meterRegistry;
        this.visitLogRecorder = visitLogRecorder;
    }

    public void recordPageVisit(String method, String requestUri, String clientIp) {
        if (!"GET".equalsIgnoreCase(method)) {
            return;
        }
        String page = normalizedPage(requestUri);
        Counter.builder("visit.page.views")
                .description("Page views served by MonkeyShop")
                .tag("page", page)
                .register(meterRegistry)
                .increment();
        visitLogRecorder.recordVisit(clientIp);
    }

    private static String normalizedPage(String requestUri) {
        if (!StringUtils.hasText(requestUri) || "/".equals(requestUri)) {
            return "home";
        }
        return requestUri.replaceFirst("^/", "").replace(".html", "");
    }
}
