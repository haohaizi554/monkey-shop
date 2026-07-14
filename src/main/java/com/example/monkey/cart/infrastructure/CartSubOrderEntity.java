package com.example.monkey.cart.infrastructure;

import com.example.monkey.cart.domain.CartCheckoutStatus;
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
@Table(name = "cart_sub_order")
public class CartSubOrderEntity extends TenantScopedJpaEntity {

    @Id
    private Long id;

    @Column(nullable = false)
    private Long checkoutId;

    @Column(nullable = false, unique = true, length = 64)
    private String orderNo;

    @Column(nullable = false)
    private Long shopId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal originalAmount;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal storeDiscountAmount;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal platformDiscountAmount;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal discountAmount;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal payableAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private CartCheckoutStatus status;

    private Long formalOrderId;

    @Column(nullable = false)
    private LocalDateTime createTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getCheckoutId() {
        return checkoutId;
    }

    public void setCheckoutId(Long checkoutId) {
        this.checkoutId = checkoutId;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public Long getShopId() {
        return shopId;
    }

    public void setShopId(Long shopId) {
        this.shopId = shopId;
    }

    public BigDecimal getOriginalAmount() {
        return originalAmount;
    }

    public void setOriginalAmount(BigDecimal originalAmount) {
        this.originalAmount = originalAmount;
    }

    public BigDecimal getStoreDiscountAmount() {
        return storeDiscountAmount;
    }

    public void setStoreDiscountAmount(BigDecimal storeDiscountAmount) {
        this.storeDiscountAmount = storeDiscountAmount;
    }

    public BigDecimal getPlatformDiscountAmount() {
        return platformDiscountAmount;
    }

    public void setPlatformDiscountAmount(BigDecimal platformDiscountAmount) {
        this.platformDiscountAmount = platformDiscountAmount;
    }

    public BigDecimal getDiscountAmount() {
        return discountAmount;
    }

    public void setDiscountAmount(BigDecimal discountAmount) {
        this.discountAmount = discountAmount;
    }

    public BigDecimal getPayableAmount() {
        return payableAmount;
    }

    public void setPayableAmount(BigDecimal payableAmount) {
        this.payableAmount = payableAmount;
    }

    public CartCheckoutStatus getStatus() {
        return status;
    }

    public void setStatus(CartCheckoutStatus status) {
        this.status = status;
    }

    public Long getFormalOrderId() {
        return formalOrderId;
    }

    public void setFormalOrderId(Long formalOrderId) {
        this.formalOrderId = formalOrderId;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
