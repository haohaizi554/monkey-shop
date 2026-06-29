package com.example.monkey.infrastructure.storage;

import com.example.monkey.domain.storage.ObjectStorageKey;
import com.example.monkey.domain.storage.ObjectStorageService;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.Http.Method;
import io.minio.MinioClient;
import io.minio.PostPolicy;
import io.minio.PutObjectArgs;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@ConditionalOnProperty(name = "app.storage.provider", havingValue = "minio")
public class MinioObjectStorageService implements ObjectStorageService {

    private final MinioClient minioClient;
    private final String bucket;
    private final String publicBaseUrl;
    private final String endpointBaseUrl;

    public MinioObjectStorageService(
            @Value("${app.storage.minio.endpoint}") String endpoint,
            @Value("${app.storage.minio.access-key}") String accessKey,
            @Value("${app.storage.minio.secret-key}") String secretKey,
            @Value("${app.storage.minio.bucket}") String bucket,
            @Value("${app.storage.public-base-url:}") String publicBaseUrl) {
        this(
                MinioClient.builder()
                        .endpoint(endpoint)
                        .credentials(accessKey, secretKey)
                        .build(),
                bucket,
                publicBaseUrl,
                endpoint);
    }

    MinioObjectStorageService(MinioClient minioClient, String bucket, String publicBaseUrl, String endpointBaseUrl) {
        this.minioClient = minioClient;
        this.bucket = bucket;
        this.publicBaseUrl = stripTrailingSlash(publicBaseUrl);
        this.endpointBaseUrl = stripTrailingSlash(endpointBaseUrl);
    }

    @Override
    public StoredObject store(String objectKey, byte[] content, String contentType) throws IOException {
        String normalizedObjectKey = ObjectStorageKey.normalize(objectKey);
        try (ByteArrayInputStream input = new ByteArrayInputStream(content)) {
            minioClient.putObject(
                    PutObjectArgs.builder().bucket(bucket).object(normalizedObjectKey).contentType(contentType).stream(
                                    input, (long) content.length, -1L)
                            .build());
            return new StoredObject(normalizedObjectKey, publicUrl(normalizedObjectKey));
        } catch (Exception e) {
            throw new IOException("object storage put failed", e);
        }
    }

    @Override
    public PresignedGetUrl createPresignedGetUrl(String objectKey, Duration ttl) throws IOException {
        String normalizedObjectKey = ObjectStorageKey.normalize(objectKey);
        try {
            String url = minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(normalizedObjectKey)
                    .expiry(Math.toIntExact(ttl.toSeconds()))
                    .build());
            return new PresignedGetUrl(normalizedObjectKey, url, Instant.now().plus(ttl));
        } catch (Exception e) {
            throw new IOException("object storage presigned GET failed", e);
        }
    }

    @Override
    public PresignedPostForm createPresignedPost(String objectKey, String contentType, long maxSizeBytes, Duration ttl)
            throws IOException {
        String normalizedObjectKey = ObjectStorageKey.normalize(objectKey);
        ZonedDateTime expiresAt = ZonedDateTime.now().plus(ttl);
        PostPolicy policy = new PostPolicy(bucket, expiresAt);
        policy.addEqualsCondition("key", normalizedObjectKey);
        policy.addStartsWithCondition("Content-Type", contentTypePrefix(contentType));
        policy.addContentLengthRangeCondition(1, maxSizeBytes);
        try {
            Map<String, String> formData = minioClient.getPresignedPostFormData(policy);
            return new PresignedPostForm(
                    normalizedObjectKey,
                    uploadUrl(),
                    Map.copyOf(formData),
                    publicUrl(normalizedObjectKey),
                    expiresAt.toInstant());
        } catch (Exception e) {
            throw new IOException("object storage presigned POST failed", e);
        }
    }

    @Override
    public String publicUrl(String objectKey) {
        String normalizedObjectKey = ObjectStorageKey.normalize(objectKey);
        if (StringUtils.hasText(publicBaseUrl)) {
            return publicBaseUrl + "/" + normalizedObjectKey;
        }
        if (StringUtils.hasText(endpointBaseUrl)) {
            return endpointBaseUrl + "/" + bucket + "/" + normalizedObjectKey;
        }
        return normalizedObjectKey;
    }

    private String uploadUrl() {
        if (StringUtils.hasText(endpointBaseUrl)) {
            return endpointBaseUrl + "/" + bucket;
        }
        return "/" + bucket;
    }

    private static String contentTypePrefix(String contentType) {
        int slash = contentType.indexOf('/');
        return slash > 0 ? contentType.substring(0, slash + 1) : contentType;
    }

    private static String stripTrailingSlash(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String stripped = value.trim();
        while (stripped.endsWith("/")) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        return stripped;
    }
}
