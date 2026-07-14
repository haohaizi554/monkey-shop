package com.example.monkey.payment.infrastructure;

import com.example.monkey.payment.domain.PaymentFailureClassification;
import com.example.monkey.payment.domain.PaymentMethod;
import com.example.monkey.payment.domain.PaymentOperationState;
import com.example.monkey.payment.domain.PaymentStatus;
import com.example.monkey.shared.infrastructure.tenant.TenantScopedJpaEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "payment_order",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_payment_order_user_key",
                        columnNames = {"tenant_id", "user_id", "idempotency_key"}))
public class PaymentOrderEntity extends TenantScopedJpaEntity {

    @Id
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String paymentNo;

    @Column(nullable = false)
    private Long orderId;

    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PaymentMethod method;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal paidAmount;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal refundedAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PaymentStatus status;

    @Column(nullable = false, length = 128)
    private String idempotencyKey;

    @Column(nullable = false, columnDefinition = "CHAR(64)")
    private String requestFingerprint;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PaymentOperationState operationState;

    @Column(nullable = false)
    private int attemptCount;

    private LocalDateTime leaseExpiresAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PaymentFailureClassification lastFailureClassification;

    @Column(length = 128)
    private String merchantToken;

    @Column(length = 2048)
    private String paymentUrl;

    @Column(precision = 10, scale = 2)
    private BigDecimal responsePaidAmount;

    @Column(precision = 10, scale = 2)
    private BigDecimal responseRefundedAmount;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private PaymentStatus responseStatus;

    @Column(length = 96)
    private String responseProviderTradeNo;

    private LocalDateTime responsePaidAt;

    @Column(length = 96)
    private String providerTradeNo;

    @Column(length = 1024)
    private String bankCardCiphertext;

    @Column(name = "bank_card_hmac", columnDefinition = "CHAR(64)")
    private String bankCardHmac;

    @Column(length = 4)
    private String bankCardLast4;

    private LocalDateTime paidAt;

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

    public String getPaymentNo() {
        return paymentNo;
    }

    public void setPaymentNo(String paymentNo) {
        this.paymentNo = paymentNo;
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

    public PaymentMethod getMethod() {
        return method;
    }

    public void setMethod(PaymentMethod method) {
        this.method = method;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public BigDecimal getPaidAmount() {
        return paidAmount;
    }

    public void setPaidAmount(BigDecimal paidAmount) {
        this.paidAmount = paidAmount;
    }

    public BigDecimal getRefundedAmount() {
        return refundedAmount;
    }

    public void setRefundedAmount(BigDecimal refundedAmount) {
        this.refundedAmount = refundedAmount;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public void setStatus(PaymentStatus status) {
        this.status = status;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
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

    public int getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(int attemptCount) {
        this.attemptCount = attemptCount;
    }

    public LocalDateTime getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public void setLeaseExpiresAt(LocalDateTime leaseExpiresAt) {
        this.leaseExpiresAt = leaseExpiresAt;
    }

    public PaymentFailureClassification getLastFailureClassification() {
        return lastFailureClassification;
    }

    public void setLastFailureClassification(PaymentFailureClassification lastFailureClassification) {
        this.lastFailureClassification = lastFailureClassification;
    }

    public String getMerchantToken() {
        return merchantToken;
    }

    public void setMerchantToken(String merchantToken) {
        this.merchantToken = merchantToken;
    }

    public String getPaymentUrl() {
        return paymentUrl;
    }

    public void setPaymentUrl(String paymentUrl) {
        this.paymentUrl = paymentUrl;
    }

    public BigDecimal getResponsePaidAmount() {
        return responsePaidAmount;
    }

    public void setResponsePaidAmount(BigDecimal responsePaidAmount) {
        this.responsePaidAmount = responsePaidAmount;
    }

    public BigDecimal getResponseRefundedAmount() {
        return responseRefundedAmount;
    }

    public void setResponseRefundedAmount(BigDecimal responseRefundedAmount) {
        this.responseRefundedAmount = responseRefundedAmount;
    }

    public PaymentStatus getResponseStatus() {
        return responseStatus;
    }

    public void setResponseStatus(PaymentStatus responseStatus) {
        this.responseStatus = responseStatus;
    }

    public String getResponseProviderTradeNo() {
        return responseProviderTradeNo;
    }

    public void setResponseProviderTradeNo(String responseProviderTradeNo) {
        this.responseProviderTradeNo = responseProviderTradeNo;
    }

    public LocalDateTime getResponsePaidAt() {
        return responsePaidAt;
    }

    public void setResponsePaidAt(LocalDateTime responsePaidAt) {
        this.responsePaidAt = responsePaidAt;
    }

    public String getProviderTradeNo() {
        return providerTradeNo;
    }

    public void setProviderTradeNo(String providerTradeNo) {
        this.providerTradeNo = providerTradeNo;
    }

    public String getBankCardCiphertext() {
        return bankCardCiphertext;
    }

    public void setBankCardCiphertext(String bankCardCiphertext) {
        this.bankCardCiphertext = bankCardCiphertext;
    }

    public String getBankCardHmac() {
        return bankCardHmac;
    }

    public void setBankCardHmac(String bankCardHmac) {
        this.bankCardHmac = bankCardHmac;
    }

    public String getBankCardLast4() {
        return bankCardLast4;
    }

    public void setBankCardLast4(String bankCardLast4) {
        this.bankCardLast4 = bankCardLast4;
    }

    public LocalDateTime getPaidAt() {
        return paidAt;
    }

    public void setPaidAt(LocalDateTime paidAt) {
        this.paidAt = paidAt;
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
