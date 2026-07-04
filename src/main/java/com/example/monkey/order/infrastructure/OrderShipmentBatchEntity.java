package com.example.monkey.order.infrastructure;

import com.example.monkey.order.domain.OrderShipmentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "order_shipment_batch")
public class OrderShipmentBatchEntity {

    @Id
    private Long id;

    @Column(nullable = false)
    private Long orderId;

    @Column(nullable = false, unique = true, length = 64)
    private String shipmentNo;

    @Column(nullable = false, length = 64)
    private String carrier;

    @Column(nullable = false, length = 96)
    private String trackingNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private OrderShipmentStatus status;

    @Column(nullable = false)
    private LocalDateTime shippedAt;

    private LocalDateTime receivedAt;

    @Column(nullable = false)
    private LocalDateTime createTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public String getShipmentNo() {
        return shipmentNo;
    }

    public void setShipmentNo(String shipmentNo) {
        this.shipmentNo = shipmentNo;
    }

    public String getCarrier() {
        return carrier;
    }

    public void setCarrier(String carrier) {
        this.carrier = carrier;
    }

    public String getTrackingNo() {
        return trackingNo;
    }

    public void setTrackingNo(String trackingNo) {
        this.trackingNo = trackingNo;
    }

    public OrderShipmentStatus getStatus() {
        return status;
    }

    public void setStatus(OrderShipmentStatus status) {
        this.status = status;
    }

    public LocalDateTime getShippedAt() {
        return shippedAt;
    }

    public void setShippedAt(LocalDateTime shippedAt) {
        this.shippedAt = shippedAt;
    }

    public LocalDateTime getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(LocalDateTime receivedAt) {
        this.receivedAt = receivedAt;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
