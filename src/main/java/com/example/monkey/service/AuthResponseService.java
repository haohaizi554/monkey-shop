package com.example.monkey.service;

import com.example.monkey.assembler.AuthDtoAssembler;
import com.example.monkey.dto.AuthLoginResponseDto;
import com.example.monkey.dto.CaptchaConfigResponseDto;
import org.springframework.stereotype.Service;

@Service
public class AuthResponseService {

    public AuthLoginResponseDto loginResponse(String role) {
        return loginResponse(role, false);
    }

    public AuthLoginResponseDto loginResponse(String role, boolean passwordChangeRequired) {
        return AuthDtoAssembler.loginResponse(role, passwordChangeRequired);
    }

    public CaptchaConfigResponseDto captchaConfig(String provider, String siteKey) {
        return AuthDtoAssembler.captchaConfig(provider, siteKey);
    }
}
