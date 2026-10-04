package com.example.monkey.shared.infrastructure.storage;

import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.shared.domain.storage.ObjectStorageKey;
import com.example.monkey.shared.domain.storage.ObjectStorageService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
        if (!StringUtils.hasText(publicBaseUrl)) {
            return null;
        }
        return objectKeyFromPublicUrl(reference, publicBaseUrl);
    }

    @Override
    public boolean exists(String objectKey) throws IOException {
        try {
            return Files.readAttributes(
                            resolveObjectPath(objectKey), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                    .isRegularFile();
        } catch (NoSuchFileException exception) {
            return false;
        }
    }

    @Override
    public List<StoredObjectMetadata> listStoredObjects() throws IOException {
        List<StoredObjectMetadata> objects = new ArrayList<>();
        for (String prefix : List.of("product/", "avatar/")) {
            Path prefixRoot = uploadRoot.resolve(prefix).normalize();
            if (Files.notExists(prefixRoot, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            if (!Files.readAttributes(prefixRoot, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                    .isDirectory()) {
                throw new IOException("object storage image prefix is not a directory: " + prefixRoot);
            }
            try (var paths = Files.walk(prefixRoot)) {
                var iterator = paths.iterator();
                while (iterator.hasNext()) {
                    Path path = iterator.next();
                    BasicFileAttributes attributes;
                    try {
                        attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    } catch (NoSuchFileException exception) {
                        throw new IOException("object storage object disappeared during listing: " + path, exception);
                    }
                    if (!attributes.isRegularFile()) {
                        continue;
                    }
                    Path normalizedPath = path.toAbsolutePath().normalize();
                    if (!normalizedPath.startsWith(uploadRoot)) {
                        throw new IOException("object storage path escapes upload root: " + path);
                    }
                    String objectKey =
                            uploadRoot.relativize(normalizedPath).toString().replace('\\', '/');
                    objects.add(new StoredObjectMetadata(
                            objectKey, Files.getLastModifiedTime(path).toInstant()));
                }
            } catch (UncheckedIOException exception) {
                throw exception.getCause();
            }
        }
        return List.copyOf(objects);
    }

    @Override
    public boolean delete(String objectKey) throws IOException {
        Files.deleteIfExists(resolveObjectPath(objectKey));
        return true;
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

    private static String directObjectKey(String reference) {
        if (reference.contains("?") || reference.contains("#")) {
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
}
