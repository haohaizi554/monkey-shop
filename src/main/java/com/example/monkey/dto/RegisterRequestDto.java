package com.example.monkey.dto;

import jakarta.validation.constraints.NotBlank;
import org.springframework.web.multipart.MultipartFile;

public record RegisterRequestDto(
        @NotBlank(message = "username is required") String username,
        @NotBlank(message = "password is required") String password,
        @NotBlank(message = "phone is required") String phone,
        String email,
        @NotBlank(message = "captcha is required") String captcha,
        MultipartFile avatarFile) {}
