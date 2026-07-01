package com.example.monkey.admin.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.admin.domain.AdminStatsReader.TrendGranularity;
import com.example.monkey.order.domain.OrderStatus;
import com.example.monkey.order.infrastructure.OrderRepository;
import com.example.monkey.order.infrastructure.OrderRepository.OrderTrendProjection;
import com.example.monkey.shared.infrastructure.observability.VisitLogRepository;
import com.example.monkey.shared.infrastructure.observability.VisitLogRepository.VisitTrendProjection;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class JpaAdminStatsReaderTest {

    @Test
    void mapsOrdersVisitsAndVisitCountFromRepositories() {
        OrderRepository orderRepository = org.mockito.Mockito.mock(OrderRepository.class);
        VisitLogRepository visitLogRepository = org.mockito.Mockito.mock(VisitLogRepository.class);
        LocalDateTime start = LocalDateTime.of(2026, 6, 1, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 6, 3, 23, 59, 59);
        List<String> returnStatuses = List.of(
                OrderStatus.RETURN_REQUESTED.label(),
                OrderStatus.WAITING_RETURN_SHIPMENT.label(),
                OrderStatus.RETURN_SHIPPING.label(),
                OrderStatus.REFUNDED.label());
        OrderTrendProjection orderTrend = mock(OrderTrendProjection.class);
        when(orderTrend.getBucketYear()).thenReturn(2026);
        when(orderTrend.getBucketMonth()).thenReturn(6);
        when(orderTrend.getBucketDay()).thenReturn(2);
        when(orderTrend.getOrderCount()).thenReturn(2L);
        when(orderTrend.getGmv()).thenReturn(new BigDecimal("42.50"));
        VisitTrendProjection visitTrend = mock(VisitTrendProjection.class);
        when(visitTrend.getBucketYear()).thenReturn(2026);
        when(visitTrend.getBucketMonth()).thenReturn(6);
        when(visitTrend.getBucketDay()).thenReturn(3);
        when(visitTrend.getVisitCount()).thenReturn(3L);
        when(orderRepository.sumGmvExcludingStatus(OrderStatus.REFUNDED.label()))
                .thenReturn(new BigDecimal("42.50"));
        when(orderRepository.count()).thenReturn(1L);
        when(orderRepository.countByStatusIn(returnStatuses)).thenReturn(0L);
        when(orderRepository.findDailyOrderTrend(start, end, OrderStatus.REFUNDED.label()))
                .thenReturn(List.of(orderTrend));
        when(visitLogRepository.findDailyVisitTrend(start, end)).thenReturn(List.of(visitTrend));
        when(visitLogRepository.count()).thenReturn(3L);

        var snapshot = new JpaAdminStatsReader(orderRepository, visitLogRepository)
                .readStats(start, end, TrendGranularity.DAILY);

        assertThat(snapshot.totalGmv()).isEqualTo(new BigDecimal("42.50"));
        assertThat(snapshot.totalOrders()).isEqualTo(1L);
        assertThat(snapshot.returnOrders()).isZero();
        assertThat(snapshot.trendStats())
                .extracting("bucket", "orderCount", "gmv", "visitCount")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("06-02", 2L, new BigDecimal("42.50"), 0L),
                        org.assertj.core.groups.Tuple.tuple("06-03", 0L, BigDecimal.ZERO, 3L));
        assertThat(snapshot.totalVisits()).isEqualTo(3L);
        verify(orderRepository).sumGmvExcludingStatus(OrderStatus.REFUNDED.label());
        verify(orderRepository).count();
        verify(orderRepository).countByStatusIn(returnStatuses);
        verify(orderRepository).findDailyOrderTrend(start, end, OrderStatus.REFUNDED.label());
        verify(orderRepository, never()).findAll();
        verify(visitLogRepository).findDailyVisitTrend(start, end);
    }

    @Test
    void readsMonthlyTrendBucketsWhenRequested() {
        OrderRepository orderRepository = org.mockito.Mockito.mock(OrderRepository.class);
        VisitLogRepository visitLogRepository = org.mockito.Mockito.mock(VisitLogRepository.class);
        LocalDateTime start = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 4, 30, 23, 59, 59);
        OrderTrendProjection orderTrend = mock(OrderTrendProjection.class);
        when(orderTrend.getBucketYear()).thenReturn(2026);
        when(orderTrend.getBucketMonth()).thenReturn(3);
        when(orderTrend.getOrderCount()).thenReturn(4L);
        when(orderTrend.getGmv()).thenReturn(new BigDecimal("120.00"));
        VisitTrendProjection visitTrend = mock(VisitTrendProjection.class);
        when(visitTrend.getBucketYear()).thenReturn(2026);
        when(visitTrend.getBucketMonth()).thenReturn(3);
        when(visitTrend.getVisitCount()).thenReturn(7L);
        when(orderRepository.findMonthlyOrderTrend(start, end, OrderStatus.REFUNDED.label()))
                .thenReturn(List.of(orderTrend));
        when(visitLogRepository.findMonthlyVisitTrend(start, end)).thenReturn(List.of(visitTrend));

        var snapshot = new JpaAdminStatsReader(orderRepository, visitLogRepository)
                .readStats(start, end, TrendGranularity.MONTHLY);

        assertThat(snapshot.trendStats())
                .extracting("bucket", "orderCount", "gmv", "visitCount")
                .containsExactly(org.assertj.core.groups.Tuple.tuple("2026-03", 4L, new BigDecimal("120.00"), 7L));
        verify(orderRepository).findMonthlyOrderTrend(start, end, OrderStatus.REFUNDED.label());
        verify(visitLogRepository).findMonthlyVisitTrend(start, end);
    }
}
