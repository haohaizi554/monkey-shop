package com.example.monkey.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.example.monkey.domain.storage.MalwareDetectedException;
import com.example.monkey.domain.storage.ObjectStorageService;
import com.example.monkey.domain.storage.UploadFile;
import com.example.monkey.dto.PresignedGetUrlResponseDto;
import com.example.monkey.dto.PresignedUploadResponseDto;
import com.example.monkey.dto.UploadResponseDto;
import com.example.monkey.infrastructure.storage.LocalObjectStorageService;
import com.example.monkey.shared.api.ErrorCode;
import com.example.monkey.shared.exception.BusinessException;
import com.example.monkey.shared.web.MultipartUploadFile;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

class FileServiceTest {

    @TempDir
    Path uploadRoot;

    @Test
    void storesAvatarUnderConfiguredUploadRoot() throws IOException {
        FileService fileService = new FileService(12_000_000L, localStorage());
        MockMultipartFile file = imageFile("avatar", "avatar.png", "png", 2, 2);

        UploadResponseDto result = fileService.uploadFile(asUploadFile(file), "avatar");

        assertThat(result.path()).startsWith("/images/avatar/").endsWith(".png");
        assertThat(result.cropped()).isFalse();
        Path storedFile = uploadRoot.resolve(result.path().substring("/images/".length()));
        assertThat(storedFile).isRegularFile();
    }

    @Test
    void storesJpegAvatarUsingJpegExtension() throws IOException {
        FileService fileService = new FileService(12_000_000L, localStorage(), file -> "image/jpeg");
        MockMultipartFile file = imageFile("avatar", "avatar.jpg", "jpeg", 2, 2);

        UploadResponseDto result = fileService.uploadFile(asUploadFile(file), "avatar");

        assertThat(result.path()).startsWith("/images/avatar/").endsWith(".jpg");
        assertThat(result.cropped()).isFalse();
    }

    @Test
    void storesImageUsingDetectedContentInsteadOfClientMetadata() throws IOException {
        FileService fileService = new FileService(12_000_000L, localStorage());
        byte[] pngBytes = imageBytes("png", 2, 2);
        MockMultipartFile file = new MockMultipartFile("avatar", "avatar.jpg", "text/plain", pngBytes);

        UploadResponseDto result = fileService.uploadFile(asUploadFile(file), "avatar");

        assertThat(result.path()).startsWith("/images/avatar/").endsWith(".png");
        assertThat(result.cropped()).isFalse();
        Path storedFile = uploadRoot.resolve(result.path().substring("/images/".length()));
        assertThat(storedFile).isRegularFile();
    }

    @Test
    void rejectsNullEmptyAndOversizedFilesBeforeReading() {
        FileService fileService = new FileService(12_000_000L, localStorage());
        MockMultipartFile empty = new MockMultipartFile("file", "empty.png", "image/png", new byte[0]);
        MockMultipartFile oversized =
                new MockMultipartFile("file", "huge.png", "image/png", new byte[(5 * 1024 * 1024) + 1]);

        assertUploadFailure(() -> fileService.uploadFile(null, "avatar"), ErrorCode.VALIDATION_ERROR, "empty file");
        assertUploadFailure(
                () -> fileService.uploadFile(asUploadFile(empty), "avatar"), ErrorCode.VALIDATION_ERROR, "empty file");
        assertUploadFailure(
                () -> fileService.uploadFile(asUploadFile(oversized), "avatar"),
                ErrorCode.VALIDATION_ERROR,
                "file too large");
    }

    @Test
    void rejectsWhenMagicNumberAndMimeDetectorDisagree() throws IOException {
        FileService fileService = new FileService(12_000_000L, localStorage(), file -> "application/pdf");
        MockMultipartFile file = imageFile("avatar", "avatar.png", "png", 2, 2);

        assertUploadFailure(
                () -> fileService.uploadFile(asUploadFile(file), "avatar"),
                ErrorCode.VALIDATION_ERROR,
                "unsupported image MIME type");
        assertThat(uploadRoot).isEmptyDirectory();
    }

    @Test
    void rejectsWhenVirusScannerReportsMalware() throws IOException {
        FileService fileService = new FileService(12_000_000L, localStorage(), file -> "image/png", content -> {
            throw new MalwareDetectedException("stream: Eicar-Test-Signature FOUND");
        });
        MockMultipartFile file = imageFile("avatar", "avatar.png", "png", 2, 2);

        assertUploadFailure(
                () -> fileService.uploadFile(asUploadFile(file), "avatar"),
                ErrorCode.VALIDATION_ERROR,
                "malware detected");
        assertThat(uploadRoot).isEmptyDirectory();
    }

    @Test
    void failsClosedWhenVirusScannerIsUnavailable() throws IOException {
        FileService fileService = new FileService(12_000_000L, localStorage(), file -> "image/png", content -> {
            throw new IOException("clamd unavailable");
        });
        MockMultipartFile file = imageFile("avatar", "avatar.png", "png", 2, 2);

        assertUploadFailure(
                () -> fileService.uploadFile(asUploadFile(file), "avatar"),
                ErrorCode.INTERNAL_ERROR,
                "virus scan unavailable");
        assertThat(uploadRoot).isEmptyDirectory();
    }

    @Test
    void rejectsUnsupportedUploadTypeBeforeWriting() {
        FileService fileService = new FileService(12_000_000L, localStorage());
        MockMultipartFile file = new MockMultipartFile("file", "x.txt", "text/plain", "not-an-image".getBytes());

        assertUploadFailure(
                () -> fileService.uploadFile(asUploadFile(file), "document"),
                ErrorCode.VALIDATION_ERROR,
                "unsupported upload type");
        assertThat(uploadRoot).isEmptyDirectory();
    }

    @Test
    void rejectsImageDimensionsOverConfiguredPixelLimit() throws IOException {
        FileService fileService = new FileService(3L, localStorage());
        MockMultipartFile file = imageFile("avatar", "avatar.png", "png", 2, 2);

        assertUploadFailure(
                () -> fileService.uploadFile(asUploadFile(file), "avatar"),
                ErrorCode.VALIDATION_ERROR,
                "image dimensions are not allowed");
        assertThat(uploadRoot).isEmptyDirectory();
    }

    @Test
    void cropsNonSquareProductImages() throws IOException {
        FileService fileService = new FileService(12_000_000L, localStorage());
        MockMultipartFile file = imageFile("product", "product.png", "png", 3, 2);

        UploadResponseDto result = fileService.uploadFile(asUploadFile(file), "product");

        assertThat(result.path()).startsWith("/images/product/").endsWith(".png");
        assertThat(result.cropped()).isTrue();
        Path storedFile = uploadRoot.resolve(result.path().substring("/images/".length()));
        BufferedImage storedImage = ImageIO.read(storedFile.toFile());
        assertThat(storedImage.getWidth()).isEqualTo(2);
        assertThat(storedImage.getHeight()).isEqualTo(2);
    }

    @Test
    void uploadResponseIncludesGeneratedVariantUrls() throws IOException {
        RecordingObjectStorageService storage = new RecordingObjectStorageService();
        ImageVariantService variantService = new ImageVariantService(
                storage, true, List.of(ImageVariantService.VariantFormat.WEBP), List.of(1), false);
        FileService fileService =
                new FileService(12_000_000L, storage, variantService, file -> "image/png", file -> {});
        MockMultipartFile file = imageFile("avatar", "avatar.png", "png", 2, 2);

        UploadResponseDto result = fileService.uploadFile(asUploadFile(file), "avatar");

        assertThat(result.path()).startsWith("https://cdn.example.test/avatar/").endsWith(".png");
        assertThat(result.variants()).hasSize(1);
        assertThat(result.variants().keySet()).containsExactly("webp-1w");
        assertThat(result.variants().get("webp-1w"))
                .startsWith("https://cdn.example.test/avatar/")
                .endsWith(".png@1w.webp");
        assertThat(storage.storedContentTypes)
                .containsEntry(result.path().substring("https://cdn.example.test/".length()), "image/png")
                .containsEntry(
                        result.variants().get("webp-1w").substring("https://cdn.example.test/".length()), "image/webp");
    }

    @Test
    void rejectsFilesWithoutAllowedMagicNumber() {
        FileService fileService = new FileService(12_000_000L, localStorage());
        MockMultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", "not-a-png".getBytes());

        assertUploadFailure(
                () -> fileService.uploadFile(asUploadFile(file), "avatar"),
                ErrorCode.VALIDATION_ERROR,
                "unsupported image format");
        assertThat(uploadRoot).isEmptyDirectory();
    }

    @Test
    void createsPresignedAvatarUploadForAllowedContentType() {
        RecordingObjectStorageService storage = new RecordingObjectStorageService();
        FileService fileService = new FileService(12_000_000L, storage, file -> "image/png", file -> {});

        PresignedUploadResponseDto result = fileService.createPresignedUpload("avatar", "image/png");

        assertThat(result.objectKey()).startsWith("avatar/").endsWith(".png");
        assertThat(result.uploadUrl()).isEqualTo("https://storage.example.test/upload");
        assertThat(result.publicUrl()).isEqualTo("https://cdn.example.test/" + result.objectKey());
        assertThat(result.formData()).containsEntry("key", result.objectKey());
        assertThat(storage.presignedPostContentType).isEqualTo("image/png");
        assertThat(storage.presignedPostMaxSizeBytes).isEqualTo(5L * 1024L * 1024L);
        assertThat(storage.presignedPostTtl).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void createsPresignedProductUploadForJpegContentType() {
        RecordingObjectStorageService storage = new RecordingObjectStorageService();
        FileService fileService = new FileService(12_000_000L, storage, file -> "image/png", file -> {});

        PresignedUploadResponseDto result = fileService.createPresignedUpload("product", "image/jpeg");

        assertThat(result.objectKey()).startsWith("product/").endsWith(".jpg");
        assertThat(storage.presignedPostContentType).isEqualTo("image/jpeg");
    }

    @Test
    void rejectsPresignedUploadForUnsupportedUploadType() {
        RecordingObjectStorageService storage = new RecordingObjectStorageService();
        FileService fileService = new FileService(12_000_000L, storage, file -> "image/png", file -> {});

        assertUploadFailure(
                () -> fileService.createPresignedUpload("document", "image/png"),
                ErrorCode.VALIDATION_ERROR,
                "unsupported upload type");
        assertThat(storage.presignedPostObjectKey).isNull();
    }

    @Test
    void rejectsPresignedUploadForUnsupportedContentType() {
        RecordingObjectStorageService storage = new RecordingObjectStorageService();
        FileService fileService = new FileService(12_000_000L, storage, file -> "image/png", file -> {});

        assertUploadFailure(
                () -> fileService.createPresignedUpload("avatar", "image/gif"),
                ErrorCode.VALIDATION_ERROR,
                "unsupported image MIME type");
        assertThat(storage.presignedPostObjectKey).isNull();
    }

    @Test
    void presignedUploadAndGetFailuresAreInternalErrors() {
        FileService fileService =
                new FileService(12_000_000L, new ThrowingObjectStorageService(), file -> "image/png", file -> {});

        assertUploadFailure(
                () -> fileService.createPresignedUpload("avatar", "image/png"),
                ErrorCode.INTERNAL_ERROR,
                "presigned upload failed");
        assertUploadFailure(
                () -> fileService.createPresignedGetUrl("product/item.png"),
                ErrorCode.INTERNAL_ERROR,
                "presigned get failed");
    }

    @Test
    void createsPresignedGetUrlWithDefaultTtl() {
        RecordingObjectStorageService storage = new RecordingObjectStorageService();
        FileService fileService = new FileService(12_000_000L, storage, file -> "image/png", file -> {});

        PresignedGetUrlResponseDto result = fileService.createPresignedGetUrl("product/item.png");

        assertThat(result.objectKey()).isEqualTo("product/item.png");
        assertThat(result.url()).isEqualTo("https://storage.example.test/get/product/item.png");
        assertThat(storage.presignedGetTtl).isEqualTo(Duration.ofMinutes(15));
    }

    private static void assertUploadFailure(ThrowingCallable action, ErrorCode errorCode, String message) {
        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(action::call)
                .withMessage(message)
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(errorCode));
    }

    private LocalObjectStorageService localStorage() {
        return new LocalObjectStorageService(uploadRoot.toString(), "");
    }

    @FunctionalInterface
    private interface ThrowingCallable {
        void call() throws Exception;
    }

    private static MockMultipartFile imageFile(
            String fieldName, String originalName, String format, int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                image.setRGB(x, y, Color.BLUE.getRGB());
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, format, output);
        return new MockMultipartFile(fieldName, originalName, "image/" + format, output.toByteArray());
    }

    private static UploadFile asUploadFile(MockMultipartFile file) {
        return MultipartUploadFile.from(file);
    }

    private static byte[] imageBytes(String format, int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                image.setRGB(x, y, Color.BLUE.getRGB());
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, format, output);
        return output.toByteArray();
    }

    private static final class RecordingObjectStorageService implements ObjectStorageService {

        private final Map<String, String> storedContentTypes = new LinkedHashMap<>();
        private String presignedPostObjectKey;
        private String presignedPostContentType;
        private long presignedPostMaxSizeBytes;
        private Duration presignedPostTtl;
        private Duration presignedGetTtl;

        @Override
        public StoredObject store(String objectKey, byte[] content, String contentType) {
            storedContentTypes.put(objectKey, contentType);
            return new StoredObject(objectKey, "https://cdn.example.test/" + objectKey);
        }

        @Override
        public PresignedGetUrl createPresignedGetUrl(String objectKey, Duration ttl) {
            presignedGetTtl = ttl;
            return new PresignedGetUrl(
                    objectKey, "https://storage.example.test/get/" + objectKey, Instant.parse("2026-01-01T00:15:00Z"));
        }

        @Override
        public PresignedPostForm createPresignedPost(
                String objectKey, String contentType, long maxSizeBytes, Duration ttl) {
            presignedPostObjectKey = objectKey;
            presignedPostContentType = contentType;
            presignedPostMaxSizeBytes = maxSizeBytes;
            presignedPostTtl = ttl;
            return new PresignedPostForm(
                    objectKey,
                    "https://storage.example.test/upload",
                    Map.of("key", objectKey, "Content-Type", contentType),
                    "https://cdn.example.test/" + objectKey,
                    Instant.parse("2026-01-01T00:15:00Z"));
        }

        @Override
        public String publicUrl(String objectKey) {
            return "https://cdn.example.test/" + objectKey;
        }
    }

    private static final class ThrowingObjectStorageService implements ObjectStorageService {

        @Override
        public StoredObject store(String objectKey, byte[] content, String contentType) throws IOException {
            throw new IOException("storage unavailable");
        }

        @Override
        public PresignedGetUrl createPresignedGetUrl(String objectKey, Duration ttl) throws IOException {
            throw new IOException("storage unavailable");
        }

        @Override
        public PresignedPostForm createPresignedPost(
                String objectKey, String contentType, long maxSizeBytes, Duration ttl) throws IOException {
            throw new IOException("storage unavailable");
        }

        @Override
        public String publicUrl(String objectKey) {
            return "https://cdn.example.test/" + objectKey;
        }
    }
}
