package com.example.monkey.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.monkey.domain.storage.ImageUsageChecker;
import com.example.monkey.infrastructure.storage.InMemoryImageReferenceService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImageCleanupServiceTest {

    @TempDir
    Path uploadRoot;

    private InMemoryImageReferenceService imageReferenceService;
    private TestImageUsageChecker imageUsageChecker;
    private ImageCleanupService imageCleanupService;

    @BeforeEach
    void setUp() {
        imageReferenceService = new InMemoryImageReferenceService();
        imageUsageChecker = new TestImageUsageChecker();
        imageCleanupService = new ImageCleanupService(imageReferenceService, imageUsageChecker, uploadRoot.toString());
    }

    @Test
    void deletesUnreferencedImageInsideConfiguredRoot() throws IOException {
        Path image = uploadRoot.resolve("avatar/test.png");
        Files.createDirectories(image.getParent());
        Files.writeString(image, "image");

        imageCleanupService.tryDelete("/images/avatar/test.png");

        assertThat(image).doesNotExist();
    }

    @Test
    void deletesGeneratedVariantSiblingsWithCanonicalImage() throws IOException {
        Path image = uploadRoot.resolve("avatar/test.png");
        Path variant = uploadRoot.resolve("avatar/test.png@320w.webp");
        Files.createDirectories(image.getParent());
        Files.writeString(image, "image");
        Files.writeString(variant, "variant");

        imageCleanupService.tryDelete("/images/avatar/test.png");

        assertThat(image).doesNotExist();
        assertThat(variant).doesNotExist();
    }

    @Test
    void keepsImageWhenReferenceCountIsPresent() throws IOException {
        Path image = uploadRoot.resolve("avatar/kept.png");
        Files.createDirectories(image.getParent());
        Files.writeString(image, "image");
        imageReferenceService.retain("/images/avatar/kept.png");

        imageCleanupService.tryDelete("/images/avatar/kept.png");

        assertThat(image).isRegularFile();
    }

    @Test
    void keepsVariantWhenCanonicalImageStillHasReferences() throws IOException {
        Path variant = uploadRoot.resolve("avatar/kept.png@320w.webp");
        Files.createDirectories(variant.getParent());
        Files.writeString(variant, "variant");
        imageReferenceService.retain("/images/avatar/kept.png");

        imageCleanupService.tryDelete("/images/avatar/kept.png@320w.webp");

        assertThat(variant).isRegularFile();
    }

    @Test
    void keepsImageWhenPersistedUsageExists() throws IOException {
        Path image = uploadRoot.resolve("avatar/kept.png");
        Files.createDirectories(image.getParent());
        Files.writeString(image, "image");
        imageUsageChecker.markUsed("/images/avatar/kept.png");

        imageCleanupService.tryDelete("/images/avatar/kept.png");

        assertThat(image).isRegularFile();
    }

    @Test
    void checksPersistedUsageAgainstCanonicalVariantPath() throws IOException {
        Path variant = uploadRoot.resolve("avatar/kept.png@320w.webp");
        Files.createDirectories(variant.getParent());
        Files.writeString(variant, "variant");
        imageUsageChecker.markUsed("/images/avatar/kept.png");

        imageCleanupService.tryDelete("/images/avatar/kept.png@320w.webp");

        assertThat(variant).isRegularFile();
    }

    @Test
    void refusesCleanupPathTraversalOutsideConfiguredRoot() throws IOException {
        Path outsideImage = uploadRoot.getParent().resolve("outside.png");
        Files.writeString(outsideImage, "image");
        String traversal = "/images/../outside.png";

        imageCleanupService.tryDelete(traversal);

        assertThat(outsideImage).isRegularFile();
    }

    @Test
    void ignoresExternalObjectStorageUrlsForLocalFilesystemCleanup() {
        imageCleanupService.tryDelete("https://cdn.example.test/avatar/alice.png");

        assertThat(uploadRoot).isEmptyDirectory();
    }

    private static final class TestImageUsageChecker implements ImageUsageChecker {

        private final Set<String> usedImagePaths = new HashSet<>();

        private void markUsed(String imagePath) {
            usedImagePaths.add(imagePath);
        }

        @Override
        public boolean isUsed(String imagePath) {
            return usedImagePaths.contains(imagePath);
        }
    }
}
