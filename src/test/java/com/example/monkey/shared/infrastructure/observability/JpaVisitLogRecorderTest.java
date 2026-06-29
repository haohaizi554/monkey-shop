package com.example.monkey.shared.infrastructure.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class JpaVisitLogRecorderTest {

    @Mock
    private VisitLogRepository visitLogRepository;

    @Test
    void writesVisitLogWithCurrentTimeAndClientIp() {
        JpaVisitLogRecorder recorder = new JpaVisitLogRecorder(
                visitLogRepository, Clock.fixed(Instant.parse("2026-06-29T00:00:00Z"), ZoneOffset.UTC));

        recorder.recordVisit("203.0.113.9");

        ArgumentCaptor<VisitLog> captor = ArgumentCaptor.forClass(VisitLog.class);
        verify(visitLogRepository).save(captor.capture());
        assertThat(captor.getValue().getIpAddress()).isEqualTo("203.0.113.9");
        assertThat(captor.getValue().getVisitTime()).isEqualTo(LocalDateTime.parse("2026-06-29T00:00:00"));
    }
}
