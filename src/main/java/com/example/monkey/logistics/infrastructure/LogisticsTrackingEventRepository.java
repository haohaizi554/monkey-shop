package com.example.monkey.logistics.infrastructure;

import com.example.monkey.logistics.domain.LogisticsCarrier;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LogisticsTrackingEventRepository extends JpaRepository<LogisticsTrackingEventEntity, Long> {

    List<LogisticsTrackingEventEntity> findByTrackingNoOrderByEventTimeAsc(String trackingNo);

    Optional<LogisticsTrackingEventEntity> findByCarrierAndEventId(LogisticsCarrier carrier, String eventId);
}
