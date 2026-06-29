package com.example.monkey.dto;

import jakarta.validation.constraints.NotBlank;

public record PasswordChangeRequestDto(
        @NotBlank(message = "phone is required") String phone,
        @NotBlank(message = "new password is required") String newPassword,
        @NotBlank(message = "captcha is required") String captcha) {}
