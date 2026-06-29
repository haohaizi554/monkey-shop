package com.example.monkey.dto;

import jakarta.validation.constraints.NotBlank;

public record UserAvatarRequestDto(
        @NotBlank(message = "avatar path is required") String avatarPath) {}
