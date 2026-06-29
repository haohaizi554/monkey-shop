package com.example.monkey.admin.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.monkey.admin.application.dto.StatsResponseDto;
import com.example.monkey.admin.domain.AdminStatsReader;
import com.example.monkey.admin.domain.AdminStatsReader.TrendGranularity;
import com.example.monkey.admin.domain.AdminStatsReader.TrendStat;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StatsServiceTest {

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
        adminStatsReader.snapshot = new AdminStatsReader.Snapshot(
                new BigDecimal("134.50"),
                4L,
                2L,
                List.of(
                        new TrendStat("06-01", 1, new BigDecimal("100.50"), 1),
                        new TrendStat("06-02", 1, BigDecimal.ZERO, 0),
                        new TrendStat("06-03", 1, new BigDecimal("25.00"), 1),
                        new TrendStat("06-10", 1, new BigDecimal("9.00"), 0)),
                9L);

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
        assertThat(adminStatsReader.trendGranularity).isEqualTo(TrendGranularity.DAILY);
    }

    @Test
    void getStatsGroupsLongRangesByMonth() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2026, 4, 5);
        adminStatsReader.snapshot = new AdminStatsReader.Snapshot(
                new BigDecimal("30.00"),
                2L,
                0L,
                List.of(
                        new TrendStat("2026-01", 1, new BigDecimal("10.00"), 0),
                        new TrendStat("2026-03", 1, new BigDecimal("20.00"), 1)),
                1L);

        StatsResponseDto result = statsService.getStats(start, end);

        assertThat(result.xAxis()).isEqualTo(List.of("2026-01", "2026-02", "2026-03", "2026-04"));
        assertThat(result.seriesOrder()).isEqualTo(List.of(1, 0, 1, 0));
        assertThat(result.seriesVisit()).isEqualTo(List.of(0, 0, 1, 0));
        assertThat(adminStatsReader.trendGranularity).isEqualTo(TrendGranularity.MONTHLY);
    }

    private static final class StubAdminStatsReader implements AdminStatsReader {
        private Snapshot snapshot = new Snapshot(BigDecimal.ZERO, 0L, 0L, List.of(), 0L);
        private LocalDateTime startInclusive;
        private LocalDateTime endInclusive;
        private TrendGranularity trendGranularity;

        @Override
        public Snapshot readStats(
                LocalDateTime startInclusive, LocalDateTime endInclusive, TrendGranularity trendGranularity) {
            this.startInclusive = startInclusive;
            this.endInclusive = endInclusive;
            this.trendGranularity = trendGranularity;
            return snapshot;
        }
    }
}
