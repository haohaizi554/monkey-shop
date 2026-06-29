package com.example.monkey.user.application;

import com.example.monkey.user.application.dto.AuthLoginResponseDto;
import com.example.monkey.user.application.dto.CaptchaConfigResponseDto;
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
