package com.example.monkey.entity;

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
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

@Entity
@Table(name = "address")
@SQLDelete(sql = "UPDATE address SET deleted = true WHERE id = ?")
@SQLRestriction("deleted = false")
@EntityListeners(PiiBlindIndexEntityListener.class)
public class Address implements PhoneBlindIndexTarget {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId; // 关联用户

    @Convert(converter = EncryptedStringAttributeConverter.class)
    @Column(length = 1024)
    private String receiverName;

    @Convert(converter = EncryptedStringAttributeConverter.class)
    @Column(length = 1024)
    private String phone;

    @Column(name = "phone_hmac", length = 64)
    private String phoneHmac;

    @Convert(converter = EncryptedStringAttributeConverter.class)
    @Column(length = 2048)
    private String detailAddress;

    private Integer isDefault; // 1是 0否

    @Column(nullable = false)
    private boolean deleted;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getReceiverName() {
        return receiverName;
    }

    public void setReceiverName(String receiverName) {
        this.receiverName = receiverName;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getPhoneHmac() {
        return phoneHmac;
    }

    public void setPhoneHmac(String phoneHmac) {
        this.phoneHmac = phoneHmac;
    }

    @Override
    public String phoneValueForBlindIndex() {
        return phone;
    }

    @Override
    public void setPhoneBlindIndex(String blindIndex) {
        this.phoneHmac = blindIndex;
    }

    public String getDetailAddress() {
        return detailAddress;
    }

    public void setDetailAddress(String detailAddress) {
        this.detailAddress = detailAddress;
    }

    public Integer getIsDefault() {
        return isDefault;
    }

    public void setIsDefault(Integer isDefault) {
        this.isDefault = isDefault;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }
}
