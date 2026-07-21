package com.example.monkey.cart.application.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CartDirectCheckoutRequestDto(
        @NotNull @Positive Long skuId,
        @NotNull @Positive Long shopId,
        @Min(1) @Max(999) int quantity,
        @NotNull @Positive Long addressId,
        String province,
        @Size(max = 20) List<String> couponCodes) {

    public CartDirectCheckoutRequestDto {
        couponCodes = couponCodes == null ? List.of() : List.copyOf(couponCodes);
    }
}
