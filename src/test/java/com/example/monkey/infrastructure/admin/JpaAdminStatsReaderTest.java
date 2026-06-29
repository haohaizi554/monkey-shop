package com.example.monkey.infrastructure.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.domain.order.OrderStatus;
import com.example.monkey.entity.Order;
import com.example.monkey.entity.VisitLog;
import com.example.monkey.repository.OrderRepository;
import com.example.monkey.repository.VisitLogRepository;
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
        Order order = new Order();
        order.setCreateTime(LocalDateTime.of(2026, 6, 2, 10, 0));
        order.setPrice(new BigDecimal("42.50"));
        order.setStatus(OrderStatus.PAID.label());
        VisitLog visitLog = new VisitLog(LocalDateTime.of(2026, 6, 3, 12, 0), "203.0.113.9");
        when(orderRepository.findAll()).thenReturn(List.of(order));
        when(visitLogRepository.findByVisitTimeBetween(start, end)).thenReturn(List.of(visitLog));
        when(visitLogRepository.count()).thenReturn(3L);

        var snapshot = new JpaAdminStatsReader(orderRepository, visitLogRepository).readStats(start, end);

        assertThat(snapshot.orders()).hasSize(1);
        assertThat(snapshot.orders().getFirst().createTime()).isEqualTo(order.getCreateTime());
        assertThat(snapshot.orders().getFirst().price()).isEqualTo(new BigDecimal("42.50"));
        assertThat(snapshot.orders().getFirst().status()).isEqualTo(OrderStatus.PAID.label());
        assertThat(snapshot.rangeVisits()).extracting("visitTime").containsExactly(visitLog.getVisitTime());
        assertThat(snapshot.totalVisits()).isEqualTo(3L);
        verify(visitLogRepository).findByVisitTimeBetween(start, end);
    }
}
