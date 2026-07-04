package com.example.monkey.tenant.infrastructure;

import com.example.monkey.shared.domain.tenant.TenantScoped;
import com.example.monkey.shared.infrastructure.tenant.TenantScopedEntityListener;
import com.example.monkey.tenant.domain.TenantBillStatus;
import com.example.monkey.tenant.domain.TenantPlan;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "tenant_bill")
@EntityListeners(TenantScopedEntityListener.class)
public class TenantBillEntity implements TenantScoped {

    @Id
    private Long id;

    private Long tenantId;

    @Column(nullable = false, columnDefinition = "CHAR(7)")
    private String billingMonth;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TenantPlan plan;

    private long orderCount;
    private BigDecimal monthlyFee;
    private BigDecimal usageFee;
    private BigDecimal totalAmount;
    private BigDecimal paymentAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TenantBillStatus status;

    private LocalDateTime generatedAt;
    private LocalDateTime reconciledAt;
    private Long version;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    @Override
    public Long getTenantId() {
        return tenantId;
    }

    @Override
    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public String getBillingMonth() {
        return billingMonth;
    }

    public void setBillingMonth(String billingMonth) {
        this.billingMonth = billingMonth;
    }

    public TenantPlan getPlan() {
        return plan;
    }

    public void setPlan(TenantPlan plan) {
        this.plan = plan;
    }

    public long getOrderCount() {
        return orderCount;
    }

    public void setOrderCount(long orderCount) {
        this.orderCount = orderCount;
    }

    public BigDecimal getMonthlyFee() {
        return monthlyFee;
    }

    public void setMonthlyFee(BigDecimal monthlyFee) {
        this.monthlyFee = monthlyFee;
    }

    public BigDecimal getUsageFee() {
        return usageFee;
    }

    public void setUsageFee(BigDecimal usageFee) {
        this.usageFee = usageFee;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }

    public BigDecimal getPaymentAmount() {
        return paymentAmount;
    }

    public void setPaymentAmount(BigDecimal paymentAmount) {
        this.paymentAmount = paymentAmount;
    }

    public TenantBillStatus getStatus() {
        return status;
    }

    public void setStatus(TenantBillStatus status) {
        this.status = status;
    }

    public LocalDateTime getGeneratedAt() {
        return generatedAt;
    }

    public void setGeneratedAt(LocalDateTime generatedAt) {
        this.generatedAt = generatedAt;
    }

    public LocalDateTime getReconciledAt() {
        return reconciledAt;
    }

    public void setReconciledAt(LocalDateTime reconciledAt) {
        this.reconciledAt = reconciledAt;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
