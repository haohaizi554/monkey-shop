package com.example.monkey.order.infrastructure;

import com.example.monkey.order.domain.OrderStore.CheckoutOrderLineRecord;
import com.example.monkey.shared.infrastructure.tenant.TenantScopedJpaEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "order_line")
public class OrderLineEntity extends TenantScopedJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long orderId;

    @Column(nullable = false)
    private Long checkoutLineId;

    @Column(nullable = false)
    private Long skuId;

    @Column(nullable = false)
    private Long shopId;

    private Long categoryId;

    @Column(nullable = false, length = 191)
    private String productName;

    @Column(length = 512)
    private String productImage;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal originalAmount;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal discountAmount;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal payableAmount;

    @Column(length = 512)
    private String couponCodes;

    @Column(nullable = false, length = 191)
    private String reservationKey;

    private Long warehouseId;

    @Column(nullable = false)
    private LocalDateTime createTime;

    protected OrderLineEntity() {}

    static OrderLineEntity from(Long orderId, CheckoutOrderLineRecord line) {
        OrderLineEntity entity = new OrderLineEntity();
        entity.orderId = orderId;
        entity.checkoutLineId = line.checkoutLineId();
        entity.skuId = line.skuId();
        entity.shopId = line.shopId();
        entity.categoryId = line.categoryId();
        entity.productName = line.productName();
        entity.productImage = line.productImage();
        entity.quantity = line.quantity();
        entity.unitPrice = line.unitPrice();
        entity.originalAmount = line.originalAmount();
        entity.discountAmount = line.discountAmount();
        entity.payableAmount = line.payableAmount();
        entity.couponCodes = line.couponCodes();
        entity.reservationKey = line.reservationKey();
        entity.warehouseId = line.warehouseId();
        entity.createTime = LocalDateTime.now();
        return entity;
    }

    CheckoutOrderLineRecord toRecord() {
        return new CheckoutOrderLineRecord(
                checkoutLineId,
                skuId,
                shopId,
                categoryId,
                productName,
                productImage,
                quantity,
                unitPrice,
                originalAmount,
                discountAmount,
                payableAmount,
                couponCodes,
                reservationKey,
                warehouseId);
    }

    public Long getId() {
        return id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public Long getCheckoutLineId() {
        return checkoutLineId;
    }

    public Long getSkuId() {
        return skuId;
    }

    public Long getShopId() {
        return shopId;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public String getProductName() {
        return productName;
    }

    public String getProductImage() {
        return productImage;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public BigDecimal getOriginalAmount() {
        return originalAmount;
    }

    public BigDecimal getDiscountAmount() {
        return discountAmount;
    }

    public BigDecimal getPayableAmount() {
        return payableAmount;
    }

    public String getCouponCodes() {
        return couponCodes;
    }

    public String getReservationKey() {
        return reservationKey;
    }

    public Long getWarehouseId() {
        return warehouseId;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }
}
