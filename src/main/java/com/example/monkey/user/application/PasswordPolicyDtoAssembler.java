package com.example.monkey.user.application;

import com.example.monkey.user.application.dto.PasswordPolicyResponseDto;
import com.example.monkey.user.domain.UserPasswordPolicy.PasswordPolicyMetadata;

public final class PasswordPolicyDtoAssembler {

    private PasswordPolicyDtoAssembler() {}

    public static PasswordPolicyResponseDto toResponse(PasswordPolicyMetadata metadata) {
        return new PasswordPolicyResponseDto(
                metadata.minLength(),
                metadata.requireUppercase(),
                metadata.requireLowercase(),
                metadata.requireDigit(),
                metadata.requireSpecial(),
                metadata.forbidWhitespace());
    }
}
