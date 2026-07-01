package com.example.monkey.user.interfaces.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginRequestDto(
        @NotBlank(message = "username is required") String username,
        @NotBlank(message = "password is required") String password,
        String captcha,
        String totp) {}
