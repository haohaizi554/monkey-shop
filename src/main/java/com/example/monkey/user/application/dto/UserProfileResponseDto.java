package com.example.monkey.user.application.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserProfileResponseDto(
        Boolean isLogin,
        String identity,
        String username,
        String avatar,
        String maskedPhone,
        Boolean passwordChangeRequired,
        Boolean passwordExpired) {

    public UserProfileResponseDto(
            Boolean isLogin,
            String identity,
            String username,
            String avatar,
            String maskedPhone,
            Boolean passwordChangeRequired) {
        this(isLogin, identity, username, avatar, maskedPhone, passwordChangeRequired, null);
    }
}
