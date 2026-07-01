package com.example.monkey.shared.interfaces.web;

import com.example.monkey.shared.application.observability.TraceIds;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class TraceIdFilter extends OncePerRequestFilter {

    private static final int MAX_TRACE_ID_LENGTH = 128;
    private static final Pattern TRACE_ID_PATTERN = Pattern.compile("[A-Za-z0-9._:-]+");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String traceId = normalizeTraceId(request.getHeader(TraceIds.HEADER));
        if (!StringUtils.hasText(traceId)) {
            traceId = TraceIds.currentOrCreate();
        } else {
            MDC.put(TraceIds.MDC_KEY, traceId);
        }
        response.setHeader(TraceIds.HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(TraceIds.MDC_KEY);
        }
    }

    private static String normalizeTraceId(String rawTraceId) {
        if (!StringUtils.hasText(rawTraceId)) {
            return null;
        }
        String traceId = rawTraceId.trim();
        if (traceId.length() > MAX_TRACE_ID_LENGTH
                || !TRACE_ID_PATTERN.matcher(traceId).matches()) {
            return null;
        }
        return traceId;
    }
}
