package com.example.monkey.user.application.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserProfileResponseDto(
        Boolean isLogin,
        String identity,
        String username,
        String avatar,
        String maskedPhone,
        Boolean passwordChangeRequired) {}
