package com.example.monkey.controller;

import com.example.monkey.entity.Order;
import com.example.monkey.entity.VisitLog;
import com.example.monkey.repository.OrderRepository;
import com.example.monkey.repository.VisitLogRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/stats")
public class StatsController {

    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private VisitLogRepository visitLogRepository;

    @GetMapping("/data")
    public Map<String, Object> getStats(
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate start,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate end,
            HttpSession session
    ) {
        if (!"ADMIN".equals(session.getAttribute("IDENTITY"))) return null;

        Map<String, Object> result = new HashMap<>();

        // 1. 默认时间范围 (如果没传，默认查近7天)
        if (start == null || end == null) {
            end = LocalDate.now(ZoneId.of("Asia/Shanghai"));
            start = end.minusDays(6);
        }
        LocalDateTime startDt = start.atStartOfDay();
        LocalDateTime endDt = end.atTime(23, 59, 59);

        // 2. 查询数据库 (真实数据)
        List<Order> orders = orderRepository.findAll();
        List<Order> rangeOrders = orders.stream()
                .filter(o -> o.getCreateTime() != null)
                .filter(o -> !o.getCreateTime().isBefore(startDt) && !o.getCreateTime().isAfter(endDt))
                .collect(Collectors.toList());

        List<VisitLog> allVisits = visitLogRepository.findAll();
        List<VisitLog> rangeVisits = allVisits.stream()
                .filter(v -> v.getVisitTime() != null)
                .filter(v -> !v.getVisitTime().isBefore(startDt) && !v.getVisitTime().isAfter(endDt))
                .collect(Collectors.toList());

        // 3. 计算顶部总览卡片 (Total)
        double totalGmv = orders.stream()
                .filter(o -> o.getPrice() != null)
                .filter(o -> !"已退款".equals(o.getStatus()))
                .mapToDouble(Order::getPrice).sum();
        long totalOrderCount = orders.size();
        long totalVisitCount = allVisits.size();
        long returnCount = orders.stream().filter(o -> o.getStatus() != null && o.getStatus().contains("退")).count();
        double returnRate = totalOrderCount == 0 ? 0 : (double) returnCount / totalOrderCount * 100;

        result.put("totalGmv", String.format("%.2f", totalGmv));
        result.put("totalOrders", totalOrderCount);
        result.put("totalVisits", totalVisitCount);
        result.put("returnRate", String.format("%.1f", returnRate));

        // 4. 生成图表数据 (Trend)
        List<String> xAxis = new ArrayList<>();
        List<Integer> seriesOrder = new ArrayList<>();
        List<Double> seriesGmv = new ArrayList<>();
        List<Integer> seriesVisit = new ArrayList<>();

        // 判断按天还是按月分组
        boolean groupByMonth = java.time.temporal.ChronoUnit.DAYS.between(start, end) > 60;
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(groupByMonth ? "yyyy-MM" : "MM-dd");

        LocalDate current = start;
        while (!current.isAfter(end)) {
            String key = current.format(formatter);
            if (!xAxis.contains(key)) xAxis.add(key);

            // 移动指针
            current = groupByMonth ? current.plusMonths(1) : current.plusDays(1);
        }

        // 填充数据
        for (String key : xAxis) {
            long orderCount = rangeOrders.stream()
                    .filter(o -> o.getCreateTime().format(formatter).equals(key)).count();
            double gmv = rangeOrders.stream()
                    .filter(o -> o.getCreateTime().format(formatter).equals(key) && !"已退款".equals(o.getStatus()))
                    .filter(o -> o.getPrice() != null)
                    .mapToDouble(Order::getPrice).sum();
            long visitCount = rangeVisits.stream()
                    .filter(v -> v.getVisitTime().format(formatter).equals(key)).count();

            seriesOrder.add((int) orderCount);
            seriesGmv.add(gmv);
            seriesVisit.add((int) visitCount);
        }

        result.put("xAxis", xAxis);
        result.put("seriesOrder", seriesOrder);
        result.put("seriesGmv", seriesGmv);
        result.put("seriesVisit", seriesVisit);

        return result;
    }
}
