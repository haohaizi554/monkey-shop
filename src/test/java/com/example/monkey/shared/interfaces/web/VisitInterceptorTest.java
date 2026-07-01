package com.example.monkey.shared.interfaces.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.example.monkey.shared.application.observability.VisitMetricsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
class VisitInterceptorTest {

    @Mock
    private VisitMetricsService visitMetricsService;

    @Test
    void recordsResolvedClientIpAndContinuesRequest() {
        VisitInterceptor interceptor = new VisitInterceptor(visitMetricsService);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/shop");
        request.addHeader("X-Forwarded-For", "203.0.113.10, 10.0.0.9");

        boolean handled = interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertThat(handled).isTrue();
        verify(visitMetricsService).recordPageVisit("GET", "/shop", "203.0.113.10");
    }
}
