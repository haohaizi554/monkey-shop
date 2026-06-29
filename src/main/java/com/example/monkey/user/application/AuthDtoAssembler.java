package com.example.monkey.user.application;

import com.example.monkey.user.application.dto.AuthLoginResponseDto;
import com.example.monkey.user.application.dto.CaptchaConfigResponseDto;

public final class AuthDtoAssembler {

    private AuthDtoAssembler() {}

    public static AuthLoginResponseDto loginResponse(String role, boolean passwordChangeRequired) {
        return new AuthLoginResponseDto(role, passwordChangeRequired);
    }

    public static CaptchaConfigResponseDto captchaConfig(String provider, String siteKey) {
        return new CaptchaConfigResponseDto(provider, siteKey);
    }
}
