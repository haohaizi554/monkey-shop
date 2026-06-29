package com.example.monkey.shared.application.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.shared.domain.storage.ObjectStorageService;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ImageVariantServiceTest {

    @Test
    void createsConfiguredWebpVariants() throws Exception {
        assertThat(ImageIO.getImageWritersByFormatName("webp").hasNext()).isTrue();
        RecordingObjectStorageService storage = new RecordingObjectStorageService();
        ImageVariantService service = new ImageVariantService(
                storage, true, List.of(ImageVariantService.VariantFormat.WEBP), List.of(1, 8), false);

        Map<String, String> variants = service.createVariants(testImage(2, 2), "avatar/alice.png");

        assertThat(variants)
                .containsEntry("webp-1w", "https://cdn.example.test/avatar/alice.png@1w.webp")
                .containsEntry("webp-2w", "https://cdn.example.test/avatar/alice.png@2w.webp");
        assertThat(storage.storedContentTypes)
                .containsEntry("avatar/alice.png@1w.webp", "image/webp")
                .containsEntry("avatar/alice.png@2w.webp", "image/webp");
        assertThat(storage.storedBytes.values())
                .allSatisfy(bytes -> assertThat(bytes).isNotEmpty());
    }

    @Test
    void skipsAvifVariantsWhenEncoderIsNotAvailableAndNotRequired() throws Exception {
        RecordingObjectStorageService storage = new RecordingObjectStorageService();
        ImageVariantService service = new ImageVariantService(
                storage, true, List.of(ImageVariantService.VariantFormat.AVIF), List.of(1), false);

        Map<String, String> variants = service.createVariants(testImage(2, 2), "avatar/alice.png");

        if (ImageIO.getImageWritersByFormatName("avif").hasNext()) {
            assertThat(variants).containsKey("avif-1w");
        } else {
            assertThat(variants).isEmpty();
            assertThat(storage.storedContentTypes).isEmpty();
        }
    }

    @Test
    void missingRequiredEncoderFailsUploadPipeline() {
        if (ImageIO.getImageWritersByFormatName("avif").hasNext()) {
            return;
        }
        RecordingObjectStorageService storage = new RecordingObjectStorageService();
        ImageVariantService service = new ImageVariantService(
                storage, true, List.of(ImageVariantService.VariantFormat.AVIF), List.of(1), true);

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> service.createVariants(testImage(2, 2), "avatar/alice.png"))
                .withMessage("No ImageIO encoder is available for avif")
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR));
    }

    @Test
    void identifiesCanonicalPathForGeneratedVariant() {
        assertThat(ImageVariantService.canonicalPathForVariant("/images/product/item.png@320w.webp"))
                .isEqualTo("/images/product/item.png");
        assertThat(ImageVariantService.canonicalPathForVariant("/images/avatar/alice.jpg@640w.avif"))
                .isEqualTo("/images/avatar/alice.jpg");
        assertThat(ImageVariantService.canonicalPathForVariant("/images/avatar/plain.png"))
                .isEqualTo("/images/avatar/plain.png");
    }

    private static BufferedImage testImage(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                image.setRGB(x, y, Color.BLUE.getRGB());
            }
        }
        return image;
    }

    private static final class RecordingObjectStorageService implements ObjectStorageService {

        private final Map<String, String> storedContentTypes = new LinkedHashMap<>();
        private final Map<String, byte[]> storedBytes = new LinkedHashMap<>();

        @Override
        public StoredObject store(String objectKey, byte[] content, String contentType) {
            storedContentTypes.put(objectKey, contentType);
            storedBytes.put(objectKey, content);
            return new StoredObject(objectKey, "https://cdn.example.test/" + objectKey);
        }

        @Override
        public PresignedGetUrl createPresignedGetUrl(String objectKey, Duration ttl) {
            return new PresignedGetUrl(
                    objectKey,
                    "https://storage.example.test/get/" + objectKey,
                    Instant.now().plus(ttl));
        }

        @Override
        public PresignedPostForm createPresignedPost(
                String objectKey, String contentType, long maxSizeBytes, Duration ttl) {
            return new PresignedPostForm(
                    objectKey,
                    "https://storage.example.test/upload",
                    Map.of("key", objectKey),
                    "https://cdn.example.test/" + objectKey,
                    Instant.now().plus(ttl));
        }

        @Override
        public String publicUrl(String objectKey) {
            return "https://cdn.example.test/" + objectKey;
        }
    }
}
