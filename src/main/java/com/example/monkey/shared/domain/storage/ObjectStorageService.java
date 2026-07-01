package com.example.monkey.shared.domain.storage;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

public interface ObjectStorageService {

    StoredObject store(String objectKey, byte[] content, String contentType) throws IOException;

    PresignedGetUrl createPresignedGetUrl(String objectKey, Duration ttl) throws IOException;

    PresignedPostForm createPresignedPost(String objectKey, String contentType, long maxSizeBytes, Duration ttl)
            throws IOException;

    String publicUrl(String objectKey);

    record StoredObject(String objectKey, String publicUrl) {}

    record PresignedGetUrl(String objectKey, String url, Instant expiresAt) {}

    record PresignedPostForm(
            String objectKey, String uploadUrl, Map<String, String> formData, String publicUrl, Instant expiresAt) {}
}
