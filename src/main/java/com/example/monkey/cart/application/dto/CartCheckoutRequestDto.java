package com.example.monkey.cart.application.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CartCheckoutRequestDto(
        @NotNull Long addressId,
        String province,
        @Size(max = 20) List<String> couponCodes) {
    public CartCheckoutRequestDto {
        couponCodes = couponCodes == null ? List.of() : List.copyOf(couponCodes);
    }
}
