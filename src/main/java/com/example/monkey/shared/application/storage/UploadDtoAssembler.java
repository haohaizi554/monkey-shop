package com.example.monkey.shared.application.storage;

import com.example.monkey.shared.application.storage.dto.PresignedGetUrlResponseDto;
import com.example.monkey.shared.application.storage.dto.PresignedUploadResponseDto;
import com.example.monkey.shared.application.storage.dto.UploadResponseDto;
import com.example.monkey.shared.domain.storage.ObjectStorageService;
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
