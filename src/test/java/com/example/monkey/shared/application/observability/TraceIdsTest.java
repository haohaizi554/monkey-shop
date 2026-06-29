package com.example.monkey.shared.application.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class TraceIdsTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void returnsExistingTraceIdFromMdc() {
        MDC.put(TraceIds.MDC_KEY, "trace-existing");

        String traceId = TraceIds.currentOrCreate();

        assertThat(traceId).isEqualTo("trace-existing");
        assertThat(MDC.get(TraceIds.MDC_KEY)).isEqualTo("trace-existing");
    }

    @Test
    void createsAndStoresTraceIdWhenMissing() {
        String traceId = TraceIds.currentOrCreate();

        assertThat(traceId).isNotBlank();
        assertThat(MDC.get(TraceIds.MDC_KEY)).isEqualTo(traceId);
    }
}
