package com.example.monkey.dto;

import jakarta.validation.constraints.NotBlank;

public record PasswordResetRequestDto(
        @NotBlank(message = "username is required") String username,
        @NotBlank(message = "phone is required") String phone,
        String email,
        @NotBlank(message = "otp is required") String otp,
        String emailToken,
        @NotBlank(message = "new password is required") String newPassword,
        String captcha) {}
