package com.example.monkey.risk.application.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record RiskAssessmentRequestDto(
        @Size(max = 256) String phone,
        @Size(max = 512) String deviceFingerprint,
        @Size(max = 64) String clientIp,
        Long productId,
        Long orderId,
        Long seckillActivityId,
        Long sellerUserId,
        @DecimalMin("0.00") BigDecimal priceBefore,
        @DecimalMin("0.00") BigDecimal priceAfter,
        @Size(max = 16) String totpCode) {}
