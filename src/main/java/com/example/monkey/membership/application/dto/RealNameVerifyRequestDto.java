package com.example.monkey.membership.application.dto;

import jakarta.validation.constraints.NotBlank;

public record RealNameVerifyRequestDto(
        @NotBlank String realName, @NotBlank String idCardNo) {}
