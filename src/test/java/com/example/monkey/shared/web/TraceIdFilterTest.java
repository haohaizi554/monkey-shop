package com.example.monkey.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    @Test
    void usesValidIncomingTraceIdAndClearsMdc() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceIds.HEADER, "trace-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, assertingChain("trace-123"));

        assertThat(response.getHeader(TraceIds.HEADER)).isEqualTo("trace-123");
        assertThat(MDC.get(TraceIds.MDC_KEY)).isNull();
    }

    @Test
    void replacesInvalidTraceIdBeforeItReachesMdc() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceIds.HEADER, "bad trace\r\nx");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(
                request,
                response,
                (servletRequest, servletResponse) ->
                        assertThat(MDC.get(TraceIds.MDC_KEY)).doesNotContain("bad trace"));

        assertThat(response.getHeader(TraceIds.HEADER)).isNotBlank();
        assertThat(response.getHeader(TraceIds.HEADER)).isNotEqualTo("bad trace\r\nx");
        assertThat(MDC.get(TraceIds.MDC_KEY)).isNull();
    }

    private static FilterChain assertingChain(String expectedTraceId) {
        return (request, response) -> assertThat(MDC.get(TraceIds.MDC_KEY)).isEqualTo(expectedTraceId);
    }
}
