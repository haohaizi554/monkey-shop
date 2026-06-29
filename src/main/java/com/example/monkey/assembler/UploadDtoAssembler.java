package com.example.monkey.assembler;

import com.example.monkey.domain.storage.ObjectStorageService;
import com.example.monkey.dto.PresignedGetUrlResponseDto;
import com.example.monkey.dto.PresignedUploadResponseDto;
import com.example.monkey.dto.UploadResponseDto;
import java.util.Map;

public final class UploadDtoAssembler {

    private UploadDtoAssembler() {}

    public static UploadResponseDto uploadResponse(String path, boolean cropped) {
        return uploadResponse(path, cropped, Map.of());
    }

    public static UploadResponseDto uploadResponse(String path, boolean cropped, Map<String, String> variants) {
        return new UploadResponseDto(path, cropped, variants);
    }

    public static PresignedUploadResponseDto presignedUploadResponse(ObjectStorageService.PresignedPostForm form) {
        return new PresignedUploadResponseDto(
                form.objectKey(), form.uploadUrl(), form.formData(), form.publicUrl(), form.expiresAt());
    }

    public static PresignedGetUrlResponseDto presignedGetUrlResponse(ObjectStorageService.PresignedGetUrl url) {
        return new PresignedGetUrlResponseDto(url.objectKey(), url.url(), url.expiresAt());
    }
}
