package com.example.monkey.logistics.infrastructure;

import com.example.monkey.logistics.domain.LogisticsCarrier;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LogisticsWebhookLogRepository extends JpaRepository<LogisticsWebhookLogEntity, Long> {

    @Modifying
    @Query(value = """
            INSERT IGNORE INTO logistics_webhook_log
                (tenant_id, id, carrier, event_id, tracking_no, source_ip, create_time)
            VALUES
                (:tenantId, :id, :carrier, :eventId, :trackingNo, :sourceIp, CURRENT_TIMESTAMP(6))
            """, nativeQuery = true)
    int reserve(
            @Param("tenantId") Long tenantId,
            @Param("id") Long id,
            @Param("carrier") String carrier,
            @Param("trackingNo") String trackingNo,
            @Param("eventId") String eventId,
            @Param("sourceIp") String sourceIp);

    Optional<LogisticsWebhookLogEntity> findByTenantIdAndCarrierAndEventId(
            Long tenantId, LogisticsCarrier carrier, String eventId);
}
