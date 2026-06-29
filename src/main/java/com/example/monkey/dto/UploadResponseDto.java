package com.example.monkey.dto;

import java.util.Map;

public record UploadResponseDto(String path, boolean cropped, Map<String, String> variants) {

    public UploadResponseDto(String path, boolean cropped) {
        this(path, cropped, Map.of());
    }

    public UploadResponseDto {
        variants = variants == null ? Map.of() : Map.copyOf(variants);
    }
}
