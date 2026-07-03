package com.example.monkey.marketing.domain;

import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import java.time.LocalDateTime;
import java.util.Objects;

public record SeckillActivity(
        Long id,
        Long skuId,
        String activityName,
        int stockQuantity,
        int soldQuantity,
        int perUserLimit,
        LocalDateTime startTime,
        LocalDateTime endTime) {

    public SeckillActivity {
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(skuId, "skuId is required");
        Objects.requireNonNull(startTime, "startTime is required");
        Objects.requireNonNull(endTime, "endTime is required");
        if (stockQuantity < 0 || soldQuantity < 0 || soldQuantity > stockQuantity || perUserLimit < 1) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Invalid seckill stock");
        }
    }

    public SeckillActivity reserve(int quantity, int alreadyPurchased, LocalDateTime now) {
        if (now.isBefore(startTime) || now.isAfter(endTime)) {
            throw new BusinessException(ErrorCode.CONFLICT, "Seckill activity is not active");
        }
        if (quantity < 1 || alreadyPurchased + quantity > perUserLimit) {
            throw new BusinessException(ErrorCode.CONFLICT, "Per-user seckill limit exceeded");
        }
        if (soldQuantity + quantity > stockQuantity) {
            throw new BusinessException(ErrorCode.OUT_OF_STOCK, "Seckill stock exhausted");
        }
        return new SeckillActivity(
                id, skuId, activityName, stockQuantity, soldQuantity + quantity, perUserLimit, startTime, endTime);
    }
}
