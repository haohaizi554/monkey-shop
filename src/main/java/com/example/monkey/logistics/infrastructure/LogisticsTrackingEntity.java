package com.example.monkey.logistics.infrastructure;

import com.example.monkey.logistics.domain.LogisticsCarrier;
import com.example.monkey.logistics.domain.TrackingStatus;
import com.example.monkey.shared.infrastructure.tenant.TenantScopedJpaEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "logistics_tracking")
public class LogisticsTrackingEntity extends TenantScopedJpaEntity {

    @Id
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String trackingNo;

    @Column(nullable = false)
    private Long orderId;

    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private LogisticsCarrier carrier;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TrackingStatus status;

    @Column(length = 1024)
    private String recipientPhoneCiphertext;

    @Column(name = "recipient_phone_hmac", columnDefinition = "CHAR(64)")
    private String recipientPhoneHmac;

    @Column(length = 2048)
    private String addressCiphertext;

    @Column(name = "address_hmac", columnDefinition = "CHAR(64)")
    private String addressHmac;

    @Column(length = 64)
    private String province;

    @Column(length = 64)
    private String city;

    @Column(length = 64)
    private String district;

    @Column(length = 255)
    private String detailSummary;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal freightAmount;

    @Column(nullable = false)
    private Integer etaHours;

    @Column(nullable = false, length = 128)
    private String idempotencyKey;

    private LocalDateTime pickedUpAt;

    private LocalDateTime inTransitAt;

    private LocalDateTime outForDeliveryAt;

    private LocalDateTime signedAt;

    @Column(nullable = false)
    private LocalDateTime createTime;

    @Column(nullable = false)
    private LocalDateTime updateTime;

    @Version
    private Long version;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTrackingNo() {
        return trackingNo;
    }

    public void setTrackingNo(String trackingNo) {
        this.trackingNo = trackingNo;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public LogisticsCarrier getCarrier() {
        return carrier;
    }

    public void setCarrier(LogisticsCarrier carrier) {
        this.carrier = carrier;
    }

    public TrackingStatus getStatus() {
        return status;
    }

    public void setStatus(TrackingStatus status) {
        this.status = status;
    }

    public String getRecipientPhoneCiphertext() {
        return recipientPhoneCiphertext;
    }

    public void setRecipientPhoneCiphertext(String recipientPhoneCiphertext) {
        this.recipientPhoneCiphertext = recipientPhoneCiphertext;
    }

    public String getRecipientPhoneHmac() {
        return recipientPhoneHmac;
    }

    public void setRecipientPhoneHmac(String recipientPhoneHmac) {
        this.recipientPhoneHmac = recipientPhoneHmac;
    }

    public String getAddressCiphertext() {
        return addressCiphertext;
    }

    public void setAddressCiphertext(String addressCiphertext) {
        this.addressCiphertext = addressCiphertext;
    }

    public String getAddressHmac() {
        return addressHmac;
    }

    public void setAddressHmac(String addressHmac) {
        this.addressHmac = addressHmac;
    }

    public String getProvince() {
        return province;
    }

    public void setProvince(String province) {
        this.province = province;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getDistrict() {
        return district;
    }

    public void setDistrict(String district) {
        this.district = district;
    }

    public String getDetailSummary() {
        return detailSummary;
    }

    public void setDetailSummary(String detailSummary) {
        this.detailSummary = detailSummary;
    }

    public BigDecimal getFreightAmount() {
        return freightAmount;
    }

    public void setFreightAmount(BigDecimal freightAmount) {
        this.freightAmount = freightAmount;
    }

    public Integer getEtaHours() {
        return etaHours;
    }

    public void setEtaHours(Integer etaHours) {
        this.etaHours = etaHours;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public LocalDateTime getPickedUpAt() {
        return pickedUpAt;
    }

    public void setPickedUpAt(LocalDateTime pickedUpAt) {
        this.pickedUpAt = pickedUpAt;
    }

    public LocalDateTime getInTransitAt() {
        return inTransitAt;
    }

    public void setInTransitAt(LocalDateTime inTransitAt) {
        this.inTransitAt = inTransitAt;
    }

    public LocalDateTime getOutForDeliveryAt() {
        return outForDeliveryAt;
    }

    public void setOutForDeliveryAt(LocalDateTime outForDeliveryAt) {
        this.outForDeliveryAt = outForDeliveryAt;
    }

    public LocalDateTime getSignedAt() {
        return signedAt;
    }

    public void setSignedAt(LocalDateTime signedAt) {
        this.signedAt = signedAt;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
