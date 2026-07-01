package com.example.monkey.admin.interfaces;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.admin.application.StatsService;
import com.example.monkey.admin.application.dto.StatsResponseDto;
import com.example.monkey.admin.interfaces.dto.AuditTraceRequestDto;
import com.example.monkey.admin.interfaces.dto.StatsQueryRequestDto;
import com.example.monkey.shared.application.observability.AuditService;
import com.example.monkey.shared.application.observability.dto.AuditTraceEventDto;
import com.example.monkey.shared.interfaces.dto.Result;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StatsControllerTest {

    @Mock
    private AuditService auditService;

    @Mock
    private StatsService statsService;

    private StatsController controller;

    @BeforeEach
    void setUp() {
        controller = new StatsController(auditService, statsService);
    }

    @Test
    void getAuditTraceDelegatesTraceIdToAuditService() {
        AuditTraceEventDto event = new AuditTraceEventDto(
                1L,
                "LOGIN_FAILURE",
                "FAILURE",
                7L,
                "USER",
                "hash",
                "203.0.113.10",
                "trace-admin-1",
                "result=failed",
                LocalDateTime.parse("2026-06-29T10:15:30"));
        when(auditService.findByTraceId("trace-admin-1")).thenReturn(List.of(event));

        Result<List<AuditTraceEventDto>> result = controller.getAuditTrace(new AuditTraceRequestDto("trace-admin-1"));

        assertThat(result.code()).isEqualTo("OK");
        assertThat(result.data()).containsExactly(event);
        verify(auditService).findByTraceId("trace-admin-1");
    }

    @Test
    void getStatsDelegatesDateRangeToService() {
        LocalDate start = LocalDate.of(2026, 6, 1);
        LocalDate end = LocalDate.of(2026, 6, 3);
        StatsResponseDto stats = new StatsResponseDto(
                "10.00", 2, 3, "0.0", List.of("06-01"), List.of(2), List.of(BigDecimal.TEN), List.of(3));
        when(statsService.getStats(start, end)).thenReturn(stats);

        Result<StatsResponseDto> result = controller.getStats(new StatsQueryRequestDto(start, end));

        assertThat(result.code()).isEqualTo("OK");
        assertThat(result.data()).isSameAs(stats);
        verify(statsService).getStats(start, end);
    }
}
