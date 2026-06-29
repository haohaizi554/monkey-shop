package com.example.monkey.shared.interfaces.storage.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.multipart.MultipartFile;

public record UploadRequestDto(
        @NotNull(message = "file is required") MultipartFile file,
        @NotBlank(message = "upload type is required") String type) {}
