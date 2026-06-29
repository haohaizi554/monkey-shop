package com.example.monkey.entity;

import com.example.monkey.domain.order.OrderEvent;
import com.example.monkey.domain.order.OrderStatus;
import com.example.monkey.domain.order.OrderTransitionPolicy;
import com.example.monkey.domain.privacy.PhoneBlindIndexTarget;
import com.example.monkey.infrastructure.privacy.EncryptedStringAttributeConverter;
import com.example.monkey.infrastructure.privacy.PiiBlindIndexEntityListener;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

@Entity
@Table(name = "orders")
@SQLDelete(sql = "UPDATE orders SET deleted = true, version = version + 1 WHERE id = ? AND version = ?")
@SQLRestriction("deleted = false")
@EntityListeners(PiiBlindIndexEntityListener.class)
public class Order implements PhoneBlindIndexTarget {
    public static final String STATUS_TRANSITION_NOT_ALLOWED = OrderTransitionPolicy.STATUS_TRANSITION_NOT_ALLOWED;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true)
    private String orderNo;

    private Long userId;

    // 买家快照 (新增)
    @Convert(converter = EncryptedStringAttributeConverter.class)
    @Column(length = 1024)
    private String buyerName;

    private String buyerAvatar;

    // 商品快照
    private Long productId;
    private String productName;
    private String productImage;

    @Column(precision = 10, scale = 2)
    private BigDecimal price;

    private String description;

    // 地址快照
    @Convert(converter = EncryptedStringAttributeConverter.class)
    @Column(length = 1024)
    private String receiverName;

    @Convert(converter = EncryptedStringAttributeConverter.class)
    @Column(length = 1024)
    private String receiverPhone;

    @Column(name = "receiver_phone_hmac", length = 64)
    private String receiverPhoneHmac;

    @Convert(converter = EncryptedStringAttributeConverter.class)
    @Column(length = 2048)
    private String addressSnapshot;

    private LocalDateTime shippingTime;

    private String status;

    @Column(nullable = false)
    private boolean deleted;

    @Column(nullable = false)
    private boolean userHidden;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "create_time", insertable = false, updatable = false)
    private LocalDateTime createTime;

    public static Order place(String orderNo, User buyer, Monkey product, Address address) {
        Order order = new Order();
        order.setOrderNo(orderNo);
        order.setUserId(buyer.getId());
        order.setBuyerName(buyer.getUsername());
        order.setBuyerAvatar(buyer.getAvatar());
        order.setProductId(product.getId());
        order.setProductName(product.getName());
        order.setProductImage(product.getImageUrl());
        order.setPrice(product.getPrice());
        order.setDescription(product.getDescription());
        order.setReceiverName(address.getReceiverName());
        order.setReceiverPhone(address.getPhone());
        order.setAddressSnapshot(address.getDetailAddress());
        order.markPaid();
        return order;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getBuyerName() {
        return buyerName;
    }

    public void setBuyerName(String buyerName) {
        this.buyerName = buyerName;
    }

    public String getBuyerAvatar() {
        return buyerAvatar;
    }

    public void setBuyerAvatar(String buyerAvatar) {
        this.buyerAvatar = buyerAvatar;
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public String getProductImage() {
        return productImage;
    }

    public void setProductImage(String productImage) {
        this.productImage = productImage;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getReceiverName() {
        return receiverName;
    }

    public void setReceiverName(String receiverName) {
        this.receiverName = receiverName;
    }

    public String getReceiverPhone() {
        return receiverPhone;
    }

    public void setReceiverPhone(String receiverPhone) {
        this.receiverPhone = receiverPhone;
    }

    public String getReceiverPhoneHmac() {
        return receiverPhoneHmac;
    }

    public void setReceiverPhoneHmac(String receiverPhoneHmac) {
        this.receiverPhoneHmac = receiverPhoneHmac;
    }

    @Override
    public String phoneValueForBlindIndex() {
        return receiverPhone;
    }

    @Override
    public void setPhoneBlindIndex(String blindIndex) {
        this.receiverPhoneHmac = blindIndex;
    }

    public String getAddressSnapshot() {
        return addressSnapshot;
    }

    public void setAddressSnapshot(String addressSnapshot) {
        this.addressSnapshot = addressSnapshot;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public boolean hasStatus(OrderStatus status) {
        return status.matches(this.status);
    }

    public void markStatus(OrderStatus status) {
        this.status = status.label();
    }

    public void markPaid() {
        markStatus(OrderStatus.PAID);
    }

    public void ship(LocalDateTime shippingTime) {
        transition(OrderEvent.SHIP);
        this.shippingTime = shippingTime;
    }

    public void markShipped(LocalDateTime shippingTime) {
        markStatus(OrderStatus.SHIPPED);
        this.shippingTime = shippingTime;
    }

    public void receive() {
        transition(OrderEvent.RECEIVE);
    }

    public void requestReturn() {
        transition(OrderEvent.REQUEST_RETURN);
    }

    public void approveReturn() {
        transition(OrderEvent.APPROVE_RETURN);
    }

    public void shipReturn() {
        transition(OrderEvent.SHIP_RETURN);
    }

    public void requireRefundable() {
        requireTransition(OrderEvent.REFUND);
    }

    public void refund() {
        transition(OrderEvent.REFUND);
    }

    public void hideFromUser() {
        userHidden = true;
    }

    public boolean shouldRestoreStockOnDelete() {
        return !hasStatus(OrderStatus.COMPLETED) && !hasStatus(OrderStatus.REFUNDED);
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }

    public boolean isUserHidden() {
        return userHidden;
    }

    public void setUserHidden(boolean userHidden) {
        this.userHidden = userHidden;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getShippingTime() {
        return shippingTime;
    }

    public void setShippingTime(LocalDateTime shippingTime) {
        this.shippingTime = shippingTime;
    }

    private void transition(OrderEvent event) {
        markStatus(requireTransition(event));
    }

    private OrderStatus requireTransition(OrderEvent event) {
        OrderStatus currentStatus;
        try {
            currentStatus = OrderStatus.fromStoredValue(status);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(STATUS_TRANSITION_NOT_ALLOWED);
        }
        return OrderTransitionPolicy.nextStatus(currentStatus, event)
                .orElseThrow(() -> new IllegalStateException(STATUS_TRANSITION_NOT_ALLOWED));
    }
}
