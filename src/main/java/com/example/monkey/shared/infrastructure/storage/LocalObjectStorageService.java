package com.example.monkey.shared.infrastructure.storage;

import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.shared.domain.storage.ObjectStorageKey;
import com.example.monkey.shared.domain.storage.ObjectStorageService;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@ConditionalOnProperty(name = "app.storage.provider", havingValue = "local", matchIfMissing = true)
public class LocalObjectStorageService implements ObjectStorageService {

    private final Path uploadRoot;
    private final String publicBaseUrl;

    @Autowired
    public LocalObjectStorageService(
            @Value("${app.upload.path:uploads/images}") String uploadPath,
            @Value("${app.storage.public-base-url:}") String publicBaseUrl) {
        this.uploadRoot = Path.of(uploadPath).toAbsolutePath().normalize();
        this.publicBaseUrl = stripTrailingSlash(publicBaseUrl);
    }

    LocalObjectStorageService(Path uploadRoot, String publicBaseUrl) {
        this.uploadRoot = uploadRoot.toAbsolutePath().normalize();
        this.publicBaseUrl = stripTrailingSlash(publicBaseUrl);
    }

    @Override
    public StoredObject store(String objectKey, byte[] content, String contentType) throws IOException {
        String normalizedObjectKey = ObjectStorageKey.normalize(objectKey);
        Path destination = resolveObjectPath(normalizedObjectKey);
        Files.createDirectories(destination.getParent());
        Files.write(destination, content);
        return new StoredObject(normalizedObjectKey, publicUrl(normalizedObjectKey));
    }

    @Override
    public PresignedGetUrl createPresignedGetUrl(String objectKey, Duration ttl) {
        String normalizedObjectKey = ObjectStorageKey.normalize(objectKey);
        return new PresignedGetUrl(
                normalizedObjectKey,
                publicUrl(normalizedObjectKey),
                Instant.now().plus(ttl));
    }

    @Override
    public PresignedPostForm createPresignedPost(
            String objectKey, String contentType, long maxSizeBytes, Duration ttl) {
        String normalizedObjectKey = ObjectStorageKey.normalize(objectKey);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("key", normalizedObjectKey);
        fields.put("Content-Type", contentType);
        fields.put("max-size", Long.toString(maxSizeBytes));
        return new PresignedPostForm(
                normalizedObjectKey,
                "/api/upload",
                Map.copyOf(fields),
                publicUrl(normalizedObjectKey),
                Instant.now().plus(ttl));
    }

    @Override
    public String publicUrl(String objectKey) {
        String normalizedObjectKey = ObjectStorageKey.normalize(objectKey);
        if (StringUtils.hasText(publicBaseUrl)) {
            return publicBaseUrl + "/" + normalizedObjectKey;
        }
        return "/images/" + normalizedObjectKey;
    }

    private Path resolveObjectPath(String objectKey) {
        String normalizedObjectKey = ObjectStorageKey.normalize(objectKey);
        Path destination = uploadRoot.resolve(normalizedObjectKey).normalize();
        if (!destination.startsWith(uploadRoot)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "invalid object key");
        }
        return destination;
    }

    private static String stripTrailingSlash(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String stripped = value.trim();
        while (stripped.endsWith("/")) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        return URI.create(stripped).toString();
    }
}
