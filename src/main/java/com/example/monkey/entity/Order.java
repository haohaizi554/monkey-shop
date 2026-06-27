package com.example.monkey.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "orders")
public class Order {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(unique = true)
    private String orderNo;
    private Long userId;

    // 买家快照 (新增)
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
    private String receiverName;
    private String receiverPhone;
    private String addressSnapshot;
    private LocalDateTime shippingTime;

    private String status; // 已支付, 已发货, 已完成

    @Column(name = "create_time", insertable = false, updatable = false)
    private LocalDateTime createTime;

    // --- Getter / Setter ---
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getBuyerName() { return buyerName; }
    public void setBuyerName(String buyerName) { this.buyerName = buyerName; }
    public String getBuyerAvatar() { return buyerAvatar; }
    public void setBuyerAvatar(String buyerAvatar) { this.buyerAvatar = buyerAvatar; }
    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }
    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }
    public String getProductImage() { return productImage; }
    public void setProductImage(String productImage) { this.productImage = productImage; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getReceiverName() { return receiverName; }
    public void setReceiverName(String receiverName) { this.receiverName = receiverName; }
    public String getReceiverPhone() { return receiverPhone; }
    public void setReceiverPhone(String receiverPhone) { this.receiverPhone = receiverPhone; }
    public String getAddressSnapshot() { return addressSnapshot; }
    public void setAddressSnapshot(String addressSnapshot) { this.addressSnapshot = addressSnapshot; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getShippingTime() { return shippingTime; }
    public void setShippingTime(LocalDateTime shippingTime) { this.shippingTime = shippingTime; }
}
