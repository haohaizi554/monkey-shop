package com.example.monkey.logistics.infrastructure;

import com.example.monkey.logistics.domain.LogisticsCarrier;
import com.example.monkey.shared.infrastructure.tenant.TenantScopedJpaEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "logistics_webhook_log")
public class LogisticsWebhookLogEntity extends TenantScopedJpaEntity {

    @Id
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private LogisticsCarrier carrier;

    @Column(nullable = false, length = 128)
    private String eventId;

    @Column(nullable = false, length = 64)
    private String trackingNo;

    @Column(length = 64)
    private String sourceIp;

    @Column(nullable = false)
    private LocalDateTime createTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public LogisticsCarrier getCarrier() {
        return carrier;
    }

    public void setCarrier(LogisticsCarrier carrier) {
        this.carrier = carrier;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getTrackingNo() {
        return trackingNo;
    }

    public void setTrackingNo(String trackingNo) {
        this.trackingNo = trackingNo;
    }

    public String getSourceIp() {
        return sourceIp;
    }

    public void setSourceIp(String sourceIp) {
        this.sourceIp = sourceIp;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
