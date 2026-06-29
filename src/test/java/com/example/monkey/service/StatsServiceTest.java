package com.example.monkey.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.monkey.domain.admin.AdminStatsReader;
import com.example.monkey.domain.admin.AdminStatsReader.OrderStat;
import com.example.monkey.domain.admin.AdminStatsReader.VisitStat;
import com.example.monkey.domain.order.OrderStatus;
import com.example.monkey.dto.StatsResponseDto;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StatsServiceTest {

    private static final String PAID_STATUS = OrderStatus.PAID.label();
    private static final String REFUNDED_STATUS = OrderStatus.REFUNDED.label();
    private static final String RETURN_STATUS = OrderStatus.RETURN_REQUESTED.label();

    private StubAdminStatsReader adminStatsReader;
    private StatsService statsService;

    @BeforeEach
    void setUp() {
        adminStatsReader = new StubAdminStatsReader();
        statsService = new StatsService(adminStatsReader);
    }

    @Test
    void getStatsBuildsTotalsAndDailyTrendFromReader() {
        LocalDate start = LocalDate.of(2026, 6, 1);
        LocalDate end = LocalDate.of(2026, 6, 3);
        OrderStat first = order(LocalDateTime.of(2026, 6, 1, 10, 0), new BigDecimal("100.50"), PAID_STATUS);
        OrderStat refunded = order(LocalDateTime.of(2026, 6, 2, 10, 0), new BigDecimal("50.00"), REFUNDED_STATUS);
        OrderStat returning = order(LocalDateTime.of(2026, 6, 3, 10, 0), new BigDecimal("25.00"), RETURN_STATUS);
        OrderStat outsideRange = order(LocalDateTime.of(2026, 6, 10, 10, 0), new BigDecimal("9.00"), PAID_STATUS);
        VisitStat firstVisit = new VisitStat(LocalDateTime.of(2026, 6, 1, 12, 0));
        VisitStat thirdVisit = new VisitStat(LocalDateTime.of(2026, 6, 3, 12, 0));
        adminStatsReader.snapshot = new AdminStatsReader.Snapshot(
                List.of(first, refunded, returning, outsideRange), List.of(firstVisit, thirdVisit), 9L);

        StatsResponseDto result = statsService.getStats(start, end);

        assertThat(result.totalGmv()).isEqualTo("134.50");
        assertThat(result.totalOrders()).isEqualTo(4L);
        assertThat(result.totalVisits()).isEqualTo(9L);
        assertThat(result.returnRate()).isEqualTo("50.0");
        assertThat(result.xAxis()).isEqualTo(List.of("06-01", "06-02", "06-03"));
        assertThat(result.seriesOrder()).isEqualTo(List.of(1, 1, 1));
        assertThat(result.seriesGmv())
                .isEqualTo(List.of(new BigDecimal("100.50"), BigDecimal.ZERO, new BigDecimal("25.00")));
        assertThat(result.seriesVisit()).isEqualTo(List.of(1, 0, 1));
        assertThat(adminStatsReader.startInclusive).isEqualTo(LocalDateTime.of(2026, 6, 1, 0, 0));
        assertThat(adminStatsReader.endInclusive).isEqualTo(LocalDateTime.of(2026, 6, 3, 23, 59, 59));
    }

    @Test
    void getStatsGroupsLongRangesByMonth() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2026, 4, 5);
        OrderStat january = order(LocalDateTime.of(2026, 1, 15, 10, 0), new BigDecimal("10.00"), PAID_STATUS);
        OrderStat march = order(LocalDateTime.of(2026, 3, 1, 10, 0), new BigDecimal("20.00"), PAID_STATUS);
        adminStatsReader.snapshot = new AdminStatsReader.Snapshot(
                List.of(january, march), List.of(new VisitStat(LocalDateTime.of(2026, 3, 2, 10, 0))), 1L);

        StatsResponseDto result = statsService.getStats(start, end);

        assertThat(result.xAxis()).isEqualTo(List.of("2026-01", "2026-02", "2026-03", "2026-04"));
        assertThat(result.seriesOrder()).isEqualTo(List.of(1, 0, 1, 0));
        assertThat(result.seriesVisit()).isEqualTo(List.of(0, 0, 1, 0));
    }

    private static OrderStat order(LocalDateTime createTime, BigDecimal price, String status) {
        return new OrderStat(createTime, price, status);
    }

    private static final class StubAdminStatsReader implements AdminStatsReader {
        private Snapshot snapshot = new Snapshot(List.of(), List.of(), 0L);
        private LocalDateTime startInclusive;
        private LocalDateTime endInclusive;

        @Override
        public Snapshot readStats(LocalDateTime startInclusive, LocalDateTime endInclusive) {
            this.startInclusive = startInclusive;
            this.endInclusive = endInclusive;
            return snapshot;
        }
    }
}
