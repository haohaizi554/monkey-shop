package com.example.monkey.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserProfileResponseDto(
        Boolean isLogin,
        String identity,
        String username,
        String avatar,
        String maskedPhone,
        Boolean passwordChangeRequired) {}
