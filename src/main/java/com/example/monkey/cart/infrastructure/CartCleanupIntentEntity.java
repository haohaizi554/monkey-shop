package com.example.monkey.cart.infrastructure;

import com.example.monkey.cart.domain.CartCleanupIntentStatus;
import com.example.monkey.shared.infrastructure.tenant.TenantScopedJpaEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;

@Entity
@Table(name = "cart_cleanup_intent")
public class CartCleanupIntentEntity extends TenantScopedJpaEntity {

    @Id
    private Long checkoutId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 2048)
    private String skuIds;

    @Column(nullable = false)
    private long cartTtlSeconds;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private CartCleanupIntentStatus status;

    @Column(nullable = false)
    private int attemptCount;

    @Column(nullable = false)
    private LocalDateTime nextAttemptAt;

    @Column(length = 255)
    private String lastError;

    @Column(nullable = false)
    private LocalDateTime createTime;

    @Column(nullable = false)
    private LocalDateTime updateTime;

    private LocalDateTime completedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    public Long getCheckoutId() {
        return checkoutId;
    }

    public void setCheckoutId(Long checkoutId) {
        this.checkoutId = checkoutId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getSkuIds() {
        return skuIds;
    }

    public void setSkuIds(String skuIds) {
        this.skuIds = skuIds;
    }

    public long getCartTtlSeconds() {
        return cartTtlSeconds;
    }

    public void setCartTtlSeconds(long cartTtlSeconds) {
        this.cartTtlSeconds = cartTtlSeconds;
    }

    public CartCleanupIntentStatus getStatus() {
        return status;
    }

    public void setStatus(CartCleanupIntentStatus status) {
        this.status = status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(int attemptCount) {
        this.attemptCount = attemptCount;
    }

    public LocalDateTime getNextAttemptAt() {
        return nextAttemptAt;
    }

    public void setNextAttemptAt(LocalDateTime nextAttemptAt) {
        this.nextAttemptAt = nextAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
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

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }
}
