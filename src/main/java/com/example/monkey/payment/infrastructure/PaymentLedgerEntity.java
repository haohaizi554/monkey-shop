package com.example.monkey.payment.infrastructure;

import com.example.monkey.payment.domain.PaymentLedgerStatus;
import com.example.monkey.payment.domain.PaymentLedgerType;
import com.example.monkey.payment.domain.PaymentOperationState;
import com.example.monkey.payment.domain.PaymentStatus;
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
@Table(name = "payment_ledger")
public class PaymentLedgerEntity extends TenantScopedJpaEntity {

    @Id
    private Long id;

    @Column(nullable = false)
    private Long paymentId;

    @Column(nullable = false)
    private Long orderId;

    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PaymentLedgerType ledgerType;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PaymentLedgerStatus status;

    @Column(nullable = false, length = 128)
    private String requestKey;

    @Column(columnDefinition = "CHAR(64)")
    private String requestFingerprint;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private PaymentOperationState operationState;

    @Column(length = 128)
    private String merchantToken;

    @Column(length = 96)
    private String providerTradeNo;

    @Column(precision = 10, scale = 2)
    private BigDecimal responseRefundedAmount;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private PaymentStatus responsePaymentStatus;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private PaymentLedgerStatus responseLedgerStatus;

    @Column(nullable = false)
    private LocalDateTime createTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getPaymentId() {
        return paymentId;
    }

    public void setPaymentId(Long paymentId) {
        this.paymentId = paymentId;
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

    public PaymentLedgerType getLedgerType() {
        return ledgerType;
    }

    public void setLedgerType(PaymentLedgerType ledgerType) {
        this.ledgerType = ledgerType;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public PaymentLedgerStatus getStatus() {
        return status;
    }

    public void setStatus(PaymentLedgerStatus status) {
        this.status = status;
    }

    public String getRequestKey() {
        return requestKey;
    }

    public void setRequestKey(String requestKey) {
        this.requestKey = requestKey;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public void setRequestFingerprint(String requestFingerprint) {
        this.requestFingerprint = requestFingerprint;
    }

    public PaymentOperationState getOperationState() {
        return operationState;
    }

    public void setOperationState(PaymentOperationState operationState) {
        this.operationState = operationState;
    }

    public String getMerchantToken() {
        return merchantToken;
    }

    public void setMerchantToken(String merchantToken) {
        this.merchantToken = merchantToken;
    }

    public String getProviderTradeNo() {
        return providerTradeNo;
    }

    public void setProviderTradeNo(String providerTradeNo) {
        this.providerTradeNo = providerTradeNo;
    }

    public BigDecimal getResponseRefundedAmount() {
        return responseRefundedAmount;
    }

    public void setResponseRefundedAmount(BigDecimal responseRefundedAmount) {
        this.responseRefundedAmount = responseRefundedAmount;
    }

    public PaymentStatus getResponsePaymentStatus() {
        return responsePaymentStatus;
    }

    public void setResponsePaymentStatus(PaymentStatus responsePaymentStatus) {
        this.responsePaymentStatus = responsePaymentStatus;
    }

    public PaymentLedgerStatus getResponseLedgerStatus() {
        return responseLedgerStatus;
    }

    public void setResponseLedgerStatus(PaymentLedgerStatus responseLedgerStatus) {
        this.responseLedgerStatus = responseLedgerStatus;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
