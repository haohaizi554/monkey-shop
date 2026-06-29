package com.example.monkey.dto;

import jakarta.validation.constraints.NotBlank;

public record AddressRequestDto(
        @NotBlank(message = "receiver name is required") String receiverName,
        @NotBlank(message = "phone is required") String phone,
        @NotBlank(message = "detail address is required") String detailAddress) {}
