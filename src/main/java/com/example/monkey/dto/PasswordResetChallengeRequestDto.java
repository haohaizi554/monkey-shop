package com.example.monkey.dto;

import jakarta.validation.constraints.NotBlank;

public record PasswordResetChallengeRequestDto(
        @NotBlank(message = "username is required") String username,
        @NotBlank(message = "phone is required") String phone,
        String email,
        String captcha) {}
