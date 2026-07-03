package com.example.monkey.marketing.domain;

import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import java.util.Objects;

public record GroupBuyActivity(
        Long id, Long skuId, String activityName, int targetSize, int durationHours, boolean active) {

    public GroupBuyActivity {
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(skuId, "skuId is required");
        if (targetSize < 2 || durationHours < 1) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Invalid group-buy activity");
        }
    }
}
