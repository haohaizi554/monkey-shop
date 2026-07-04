package com.example.monkey.logistics.infrastructure;

import com.example.monkey.logistics.domain.LogisticsCarrier;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LogisticsWebhookLogRepository extends JpaRepository<LogisticsWebhookLogEntity, Long> {

    Optional<LogisticsWebhookLogEntity> findByCarrierAndEventId(LogisticsCarrier carrier, String eventId);
}
