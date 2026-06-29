package com.example.monkey.infrastructure.admin;

import com.example.monkey.domain.admin.AdminStatsReader;
import com.example.monkey.entity.Order;
import com.example.monkey.entity.VisitLog;
import com.example.monkey.repository.OrderRepository;
import com.example.monkey.repository.VisitLogRepository;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

@Component
public final class JpaAdminStatsReader implements AdminStatsReader {

    private final OrderRepository orderRepository;
    private final VisitLogRepository visitLogRepository;

    public JpaAdminStatsReader(OrderRepository orderRepository, VisitLogRepository visitLogRepository) {
        this.orderRepository = orderRepository;
        this.visitLogRepository = visitLogRepository;
    }

    @Override
    public Snapshot readStats(LocalDateTime startInclusive, LocalDateTime endInclusive) {
        return new Snapshot(
                orderRepository.findAll().stream()
                        .map(JpaAdminStatsReader::toOrderStat)
                        .toList(),
                visitLogRepository.findByVisitTimeBetween(startInclusive, endInclusive).stream()
                        .map(JpaAdminStatsReader::toVisitStat)
                        .toList(),
                visitLogRepository.count());
    }

    private static OrderStat toOrderStat(Order order) {
        return new OrderStat(order.getCreateTime(), order.getPrice(), order.getStatus());
    }

    private static VisitStat toVisitStat(VisitLog visitLog) {
        return new VisitStat(visitLog.getVisitTime());
    }
}
