package com.example.monkey.shared.domain.storage;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface ObjectStorageService {

    StoredObject store(String objectKey, byte[] content, String contentType) throws IOException;

    PresignedGetUrl createPresignedGetUrl(String objectKey, Duration ttl) throws IOException;

    PresignedPostForm createPresignedPost(String objectKey, String contentType, long maxSizeBytes, Duration ttl)
            throws IOException;

    String publicUrl(String objectKey);

    /**
     * Resolves a reference that this provider generated (or a direct managed object key) to its object key.
     * Providers must return {@code null} for URLs that they did not generate.
     */
    default String resolveObjectKey(String imageReference) {
        throw new UnsupportedOperationException("Object reference resolution is unavailable");
    }

    /** Checks whether a managed object currently exists in this provider. */
    default boolean exists(String objectKey) throws IOException {
        throw new UnsupportedOperationException("Object existence checks are unavailable");
    }

    /**
     * Returns a complete snapshot of all stored image objects beneath the provider's product/ and avatar/
     * prefixes. Implementations must fail rather than return a partial result.
     */
    default List<StoredObjectMetadata> listStoredObjects() throws IOException {
        throw new UnsupportedOperationException("Object listing is unavailable");
    }

    /** Deletes an immutable object. Implementations without safe delete support must fail closed. */
    default boolean delete(String objectKey) throws IOException {
        throw new UnsupportedOperationException("Object deletion is unavailable");
    }

    record StoredObject(String objectKey, String publicUrl) {}

    record StoredObjectMetadata(String objectKey, Instant lastModified) {}

    record PresignedGetUrl(String objectKey, String url, Instant expiresAt) {}

    record PresignedPostForm(
            String objectKey, String uploadUrl, Map<String, String> formData, String publicUrl, Instant expiresAt) {}
}
