package com.example.monkey.shared.infrastructure.observability;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VisitLogRepository extends JpaRepository<VisitLog, Long> {

    @Query("""
            select year(v.visitTime) as bucketYear,
                   month(v.visitTime) as bucketMonth,
                   day(v.visitTime) as bucketDay,
                   count(v.id) as visitCount
            from VisitLog v
            where v.visitTime between :startInclusive and :endInclusive
            group by year(v.visitTime), month(v.visitTime), day(v.visitTime)
            """)
    List<VisitTrendProjection> findDailyVisitTrend(
            @Param("startInclusive") LocalDateTime startInclusive, @Param("endInclusive") LocalDateTime endInclusive);

    @Query("""
            select year(v.visitTime) as bucketYear,
                   month(v.visitTime) as bucketMonth,
                   count(v.id) as visitCount
            from VisitLog v
            where v.visitTime between :startInclusive and :endInclusive
            group by year(v.visitTime), month(v.visitTime)
            """)
    List<VisitTrendProjection> findMonthlyVisitTrend(
            @Param("startInclusive") LocalDateTime startInclusive, @Param("endInclusive") LocalDateTime endInclusive);

    long count();

    interface VisitTrendProjection {
        Integer getBucketYear();

        Integer getBucketMonth();

        Integer getBucketDay();

        Long getVisitCount();
    }
}
