package com.example.monkey.logistics.infrastructure;

import com.example.monkey.logistics.domain.LogisticsCarrier;
import com.example.monkey.logistics.domain.TrackingEvent;
import com.example.monkey.logistics.domain.TrackingStatus;
import com.example.monkey.shared.infrastructure.tenant.TenantScopedJpaEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "logistics_tracking_event")
public class LogisticsTrackingEventEntity extends TenantScopedJpaEntity {

    @Id
    private Long id;

    @Column(nullable = false)
    private Long trackingId;

    @Column(nullable = false, length = 64)
    private String trackingNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private LogisticsCarrier carrier;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TrackingEvent eventType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TrackingStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TrackingStatus toStatus;

    @Column(nullable = false, length = 128)
    private String eventId;

    @Column(nullable = false)
    private LocalDateTime eventTime;

    @Column(length = 128)
    private String location;

    @Column(length = 255)
    private String remark;

    @Column(nullable = false)
    private LocalDateTime createTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTrackingId() {
        return trackingId;
    }

    public void setTrackingId(Long trackingId) {
        this.trackingId = trackingId;
    }

    public String getTrackingNo() {
        return trackingNo;
    }

    public void setTrackingNo(String trackingNo) {
        this.trackingNo = trackingNo;
    }

    public LogisticsCarrier getCarrier() {
        return carrier;
    }

    public void setCarrier(LogisticsCarrier carrier) {
        this.carrier = carrier;
    }

    public TrackingEvent getEventType() {
        return eventType;
    }

    public void setEventType(TrackingEvent eventType) {
        this.eventType = eventType;
    }

    public TrackingStatus getFromStatus() {
        return fromStatus;
    }

    public void setFromStatus(TrackingStatus fromStatus) {
        this.fromStatus = fromStatus;
    }

    public TrackingStatus getToStatus() {
        return toStatus;
    }

    public void setToStatus(TrackingStatus toStatus) {
        this.toStatus = toStatus;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public LocalDateTime getEventTime() {
        return eventTime;
    }

    public void setEventTime(LocalDateTime eventTime) {
        this.eventTime = eventTime;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
