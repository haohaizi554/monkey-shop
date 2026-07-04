package com.example.monkey.payment.infrastructure;

import com.example.monkey.payment.domain.PaymentMethod;
import com.example.monkey.payment.domain.ReconciliationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "payment_reconciliation_report")
public class PaymentReconciliationReportEntity {

    @Id
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PaymentMethod provider;

    @Column(nullable = false)
    private LocalDate reportDate;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal platformAmount;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal providerAmount;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal diffAmount;

    @Column(nullable = false)
    private int issueCount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ReconciliationStatus status;

    @Column(nullable = false, length = 2048)
    private String encryptedReportPayload;

    @Column(nullable = false)
    private LocalDateTime createTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public PaymentMethod getProvider() {
        return provider;
    }

    public void setProvider(PaymentMethod provider) {
        this.provider = provider;
    }

    public LocalDate getReportDate() {
        return reportDate;
    }

    public void setReportDate(LocalDate reportDate) {
        this.reportDate = reportDate;
    }

    public BigDecimal getPlatformAmount() {
        return platformAmount;
    }

    public void setPlatformAmount(BigDecimal platformAmount) {
        this.platformAmount = platformAmount;
    }

    public BigDecimal getProviderAmount() {
        return providerAmount;
    }

    public void setProviderAmount(BigDecimal providerAmount) {
        this.providerAmount = providerAmount;
    }

    public BigDecimal getDiffAmount() {
        return diffAmount;
    }

    public void setDiffAmount(BigDecimal diffAmount) {
        this.diffAmount = diffAmount;
    }

    public int getIssueCount() {
        return issueCount;
    }

    public void setIssueCount(int issueCount) {
        this.issueCount = issueCount;
    }

    public ReconciliationStatus getStatus() {
        return status;
    }

    public void setStatus(ReconciliationStatus status) {
        this.status = status;
    }

    public String getEncryptedReportPayload() {
        return encryptedReportPayload;
    }

    public void setEncryptedReportPayload(String encryptedReportPayload) {
        this.encryptedReportPayload = encryptedReportPayload;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
