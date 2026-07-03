package com.example.monkey.marketing.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CouponRedeemRequestDto(
        @NotBlank String couponCode, @NotNull Long orderId) {}
