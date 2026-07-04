package com.example.monkey.shared.application.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.example.monkey.shared.domain.observability.VisitLogRecorder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class VisitMetricsServiceTest {

    @Mock
    private VisitLogRecorder visitLogRecorder;

    @Test
    void recordsPageVisitMetricAndQueuesClientIpPersistence() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        VisitMetricsService service = new VisitMetricsService(meterRegistry, visitLogRecorder);
        service.recordPageVisit("GET", "/shop.html", "203.0.113.9");

        assertThat(meterRegistry
                        .find("visit.page.views")
                        .tag("page", "shop")
                        .counter()
                        .count())
                .isEqualTo(1);
        verify(visitLogRecorder).recordVisit("203.0.113.9");
    }

    @Test
    void ignoresNonGetRequests() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        VisitMetricsService service = new VisitMetricsService(meterRegistry, visitLogRecorder);
        service.recordPageVisit("POST", "/shop.html", "203.0.113.9");

        assertThat(meterRegistry.find("visit.page.views").counter()).isNull();
        verify(visitLogRecorder, never()).recordVisit(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void recordsClientSidePageViewFromTrackingSdk() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        VisitMetricsService service = new VisitMetricsService(meterRegistry, visitLogRecorder);
        service.recordClientPageView("/shop/42", "203.0.113.42");

        assertThat(meterRegistry
                        .find("visit.page.views")
                        .tag("page", "shop/42")
                        .counter()
                        .count())
                .isEqualTo(1);
        verify(visitLogRecorder).recordVisit("203.0.113.42");
    }
}
