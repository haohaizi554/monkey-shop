package com.example.monkey.user.application;

import com.example.monkey.user.application.dto.PasswordPolicyResponseDto;
import com.example.monkey.user.domain.UserPasswordPolicy;
import com.example.monkey.user.domain.UserPasswordPolicy.PasswordPolicyMetadata;
import org.springframework.stereotype.Service;

@Service
public class PasswordPolicyQueryService {

    private final UserPasswordPolicy passwordPolicy;

    public PasswordPolicyQueryService(UserPasswordPolicy passwordPolicy) {
        this.passwordPolicy = passwordPolicy;
    }

    public PasswordPolicyResponseDto metadata() {
        PasswordPolicyMetadata metadata = passwordPolicy.metadata();
        return new PasswordPolicyResponseDto(
                metadata.minLength(),
                metadata.requireUppercase(),
                metadata.requireLowercase(),
                metadata.requireDigit(),
                metadata.requireSpecial(),
                metadata.forbidWhitespace());
    }
}
