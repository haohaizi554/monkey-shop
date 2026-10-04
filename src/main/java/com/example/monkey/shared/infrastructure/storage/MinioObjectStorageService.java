package com.example.monkey.shared.infrastructure.storage;

import com.example.monkey.shared.domain.storage.ObjectStorageKey;
import com.example.monkey.shared.domain.storage.ObjectStorageService;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.Http.Method;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.PostPolicy;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.Item;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Autowired
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

    @Override
    public String resolveObjectKey(String imageReference) {
        if (!StringUtils.hasText(imageReference)) {
            return null;
        }
        String reference = imageReference.trim();
        String directObjectKey = directObjectKey(reference);
        if (directObjectKey != null) {
            return directObjectKey;
        }
        return objectKeyFromPublicUrl(reference, publicBaseUrlForResolution());
    }

    @Override
    public boolean exists(String objectKey) throws IOException {
        String normalizedObjectKey = ObjectStorageKey.normalize(objectKey);
        try {
            if (minioClient.statObject(StatObjectArgs.builder()
                            .bucket(bucket)
                            .object(normalizedObjectKey)
                            .build())
                    == null) {
                throw new IOException("object storage stat returned no metadata");
            }
            return true;
        } catch (ErrorResponseException exception) {
            if (isMissingObject(exception)) {
                return false;
            }
            throw new IOException("object storage stat failed", exception);
        } catch (Exception exception) {
            throw new IOException("object storage stat failed", exception);
        }
    }

    @Override
    public List<StoredObjectMetadata> listStoredObjects() throws IOException {
        List<StoredObjectMetadata> objects = new ArrayList<>();
        try {
            for (String prefix : List.of("product/", "avatar/")) {
                Iterable<Result<Item>> results = minioClient.listObjects(ListObjectsArgs.builder()
                        .bucket(bucket)
                        .prefix(prefix)
                        .recursive(true)
                        .build());
                if (results == null) {
                    throw new IOException("object storage list returned no result stream");
                }
                for (Result<Item> result : results) {
                    Item item = result.get();
                    if (item == null || item.isDir()) {
                        continue;
                    }
                    String objectKey = managedObjectKey(item.objectName());
                    if (objectKey == null) {
                        throw new IOException("object storage list returned an unmanaged object key");
                    }
                    if (item.lastModified() == null) {
                        throw new IOException("object storage list returned an object without last-modified metadata");
                    }
                    objects.add(new StoredObjectMetadata(
                            objectKey, item.lastModified().toInstant()));
                }
            }
            return List.copyOf(objects);
        } catch (IOException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IOException("object storage list failed", exception);
        }
    }

    @Override
    public boolean delete(String objectKey) throws IOException {
        String normalizedObjectKey = ObjectStorageKey.normalize(objectKey);
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(normalizedObjectKey)
                    .build());
            return true;
        } catch (ErrorResponseException exception) {
            if (isMissingObject(exception)) {
                return true;
            }
            throw new IOException("object storage delete failed", exception);
        } catch (Exception exception) {
            throw new IOException("object storage delete failed", exception);
        }
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

    private String publicBaseUrlForResolution() {
        if (StringUtils.hasText(publicBaseUrl)) {
            return publicBaseUrl;
        }
        if (StringUtils.hasText(endpointBaseUrl)) {
            return endpointBaseUrl + "/" + bucket;
        }
        return "";
    }

    private static String directObjectKey(String reference) {
        if (reference.contains("?") || reference.contains("#") || hasUriScheme(reference)) {
            return null;
        }
        String normalizedReference = reference.replace('\\', '/');
        if (normalizedReference.startsWith("/images/")) {
            return managedObjectKey(normalizedReference.substring("/images/".length()));
        }
        if (normalizedReference.startsWith("/product/") || normalizedReference.startsWith("/avatar/")) {
            return managedObjectKey(normalizedReference.substring(1));
        }
        if (normalizedReference.startsWith("product/") || normalizedReference.startsWith("avatar/")) {
            return managedObjectKey(normalizedReference);
        }
        return null;
    }

    private static String objectKeyFromPublicUrl(String reference, String configuredPublicBaseUrl) {
        if (!StringUtils.hasText(configuredPublicBaseUrl)) {
            return null;
        }
        try {
            URI referenceUri = URI.create(reference);
            URI baseUri = URI.create(configuredPublicBaseUrl);
            if (!sameOrigin(referenceUri, baseUri)
                    || referenceUri.getQuery() != null
                    || referenceUri.getFragment() != null) {
                return null;
            }
            String basePath = normalizePath(baseUri.getPath());
            String referencePath = normalizePath(referenceUri.getPath());
            if (!referencePath.startsWith(basePath + "/")) {
                return null;
            }
            return managedObjectKey(referencePath.substring(basePath.length() + 1));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static boolean hasUriScheme(String value) {
        int separator = value.indexOf(':');
        return separator > 0
                && value.substring(0, separator)
                        .chars()
                        .allMatch(character -> Character.isLetterOrDigit(character)
                                || character == '+'
                                || character == '-'
                                || character == '.');
    }

    private static boolean sameOrigin(URI left, URI right) {
        return Objects.equals(normalize(left.getScheme()), normalize(right.getScheme()))
                && Objects.equals(normalize(left.getHost()), normalize(right.getHost()))
                && Objects.equals(left.getUserInfo(), right.getUserInfo())
                && left.getPort() == right.getPort();
    }

    private static String normalize(String value) {
        return value == null ? null : value.toLowerCase(java.util.Locale.ROOT);
    }

    private static String normalizePath(String path) {
        if (path == null || path.isBlank() || "/".equals(path)) {
            return "";
        }
        String normalized = path;
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String managedObjectKey(String objectKey) {
        try {
            String normalized = ObjectStorageKey.normalize(objectKey);
            return normalized.startsWith("product/") || normalized.startsWith("avatar/") ? normalized : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static boolean isMissingObject(ErrorResponseException exception) {
        String errorCode = exception.errorResponse() == null
                ? null
                : exception.errorResponse().code();
        return "NoSuchKey".equals(errorCode) || "NoSuchObject".equals(errorCode);
    }
}
