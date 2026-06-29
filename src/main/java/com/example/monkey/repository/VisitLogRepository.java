package com.example.monkey.repository;

import com.example.monkey.entity.VisitLog;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VisitLogRepository extends JpaRepository<VisitLog, Long> {
    // 查询指定时间段内的所有访问记录
    List<VisitLog> findByVisitTimeBetween(LocalDateTime start, LocalDateTime end);

    // 统计总访问量
    long count();
}
