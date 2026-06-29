package com.example.monkey.shared.web;

import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.util.StringUtils;

public final class TraceIds {

    public static final String MDC_KEY = "traceId";
    public static final String USER_ID_MDC_KEY = "userId";
    public static final String HEADER = "X-Trace-Id";

    private TraceIds() {}

    public static String currentOrCreate() {
        String traceId = MDC.get(MDC_KEY);
        if (StringUtils.hasText(traceId)) {
            return traceId;
        }
        traceId = UUID.randomUUID().toString();
        MDC.put(MDC_KEY, traceId);
        return traceId;
    }
}
