package com.example.monkey.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "address")
public class Address {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long userId; // 关联用户
    private String receiverName;
    private String phone;
    private String detailAddress;
    private Integer isDefault; // 1是 0否

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getReceiverName() { return receiverName; }
    public void setReceiverName(String receiverName) { this.receiverName = receiverName; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getDetailAddress() { return detailAddress; }
    public void setDetailAddress(String detailAddress) { this.detailAddress = detailAddress; }
    public Integer getIsDefault() { return isDefault; }
    public void setIsDefault(Integer isDefault) { this.isDefault = isDefault; }
}