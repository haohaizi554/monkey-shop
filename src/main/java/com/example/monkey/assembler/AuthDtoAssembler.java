package com.example.monkey.assembler;

import com.example.monkey.dto.AuthLoginResponseDto;
import com.example.monkey.dto.CaptchaConfigResponseDto;

public final class AuthDtoAssembler {

    private AuthDtoAssembler() {}

    public static AuthLoginResponseDto loginResponse(String role, boolean passwordChangeRequired) {
        return new AuthLoginResponseDto(role, passwordChangeRequired);
    }

    public static CaptchaConfigResponseDto captchaConfig(String provider, String siteKey) {
        return new CaptchaConfigResponseDto(provider, siteKey);
    }
}
