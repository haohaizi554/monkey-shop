package com.example.monkey.tracking.infrastructure;

import com.example.monkey.tracking.domain.TrackingEventType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TrackingEventRepository extends JpaRepository<TrackingEventEntity, Long> {

    long countByEventTypeAndOccurredAtGreaterThanEqual(TrackingEventType eventType, LocalDateTime since);

    @Query("""
            select count(distinct e.sessionId)
            from TrackingEventEntity e
            where e.occurredAt >= :since
            """)
    long countDistinctVisitors(@Param("since") LocalDateTime since);

    @Query("""
            select coalesce(sum(e.amount), 0)
            from TrackingEventEntity e
            where e.eventType = :eventType
              and e.occurredAt >= :since
            """)
    BigDecimal sumAmountByEventTypeSince(
            @Param("eventType") TrackingEventType eventType, @Param("since") LocalDateTime since);

    List<TrackingEventEntity> findByOccurredAtGreaterThanEqualOrderByOccurredAtDesc(
            LocalDateTime since, Pageable pageable);
}
