package com.example.monkey.dto;

import jakarta.validation.constraints.NotNull;
import org.springframework.web.multipart.MultipartFile;

public record UploadFileRequestDto(
        @NotNull(message = "file is required") MultipartFile file) {}
