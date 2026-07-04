package com.example.monkey.logistics.infrastructure;

import com.example.monkey.logistics.domain.FreightChargeMode;
import com.example.monkey.logistics.domain.LogisticsCarrier;
import com.example.monkey.shared.infrastructure.tenant.TenantScopedJpaEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "logistics_freight_template")
public class FreightTemplateEntity extends TenantScopedJpaEntity {

    @Id
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private LogisticsCarrier carrier;

    @Column(nullable = false, length = 64)
    private String province;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private FreightChargeMode chargeMode;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal baseWeightKg;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal baseFee;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal stepWeightKg;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal stepFee;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal itemFee;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal regionFee;

    @Column(nullable = false)
    private Integer etaHours;

    @Column(nullable = false)
    private Boolean active;

    @Column(nullable = false)
    private LocalDateTime createTime;

    @Column(nullable = false)
    private LocalDateTime updateTime;

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

    public String getProvince() {
        return province;
    }

    public void setProvince(String province) {
        this.province = province;
    }

    public FreightChargeMode getChargeMode() {
        return chargeMode;
    }

    public void setChargeMode(FreightChargeMode chargeMode) {
        this.chargeMode = chargeMode;
    }

    public BigDecimal getBaseWeightKg() {
        return baseWeightKg;
    }

    public void setBaseWeightKg(BigDecimal baseWeightKg) {
        this.baseWeightKg = baseWeightKg;
    }

    public BigDecimal getBaseFee() {
        return baseFee;
    }

    public void setBaseFee(BigDecimal baseFee) {
        this.baseFee = baseFee;
    }

    public BigDecimal getStepWeightKg() {
        return stepWeightKg;
    }

    public void setStepWeightKg(BigDecimal stepWeightKg) {
        this.stepWeightKg = stepWeightKg;
    }

    public BigDecimal getStepFee() {
        return stepFee;
    }

    public void setStepFee(BigDecimal stepFee) {
        this.stepFee = stepFee;
    }

    public BigDecimal getItemFee() {
        return itemFee;
    }

    public void setItemFee(BigDecimal itemFee) {
        this.itemFee = itemFee;
    }

    public BigDecimal getRegionFee() {
        return regionFee;
    }

    public void setRegionFee(BigDecimal regionFee) {
        this.regionFee = regionFee;
    }

    public Integer getEtaHours() {
        return etaHours;
    }

    public void setEtaHours(Integer etaHours) {
        this.etaHours = etaHours;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
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
}
