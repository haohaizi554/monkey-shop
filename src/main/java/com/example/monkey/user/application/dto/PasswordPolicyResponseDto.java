package com.example.monkey.user.application.dto;

public record PasswordPolicyResponseDto(
        int minLength,
        boolean requireUppercase,
        boolean requireLowercase,
        boolean requireDigit,
        boolean requireSpecial,
        boolean forbidWhitespace) {}
