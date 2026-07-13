package com.example.monkey.user.application;

import com.example.monkey.user.application.dto.PasswordPolicyResponseDto;
import com.example.monkey.user.domain.UserPasswordPolicy;
import org.springframework.stereotype.Service;

@Service
public class PasswordPolicyQueryService {

    private final UserPasswordPolicy passwordPolicy;

    public PasswordPolicyQueryService(UserPasswordPolicy passwordPolicy) {
        this.passwordPolicy = passwordPolicy;
    }

    public PasswordPolicyResponseDto metadata() {
        return PasswordPolicyDtoAssembler.toResponse(passwordPolicy.metadata());
    }
}
