package com.example.monkey.service;

import com.example.monkey.domain.storage.ImageReferenceService;
import com.example.monkey.domain.storage.ImageUsageChecker;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ImageCleanupService {

    private static final Logger log = LoggerFactory.getLogger(ImageCleanupService.class);

    private final ImageReferenceService imageReferenceService;
    private final ImageUsageChecker imageUsageChecker;
    private final Path uploadRoot;

    public ImageCleanupService(
            ImageReferenceService imageReferenceService,
            ImageUsageChecker imageUsageChecker,
            @Value("${app.upload.path:uploads/images}") String uploadPath) {
        this.imageReferenceService = imageReferenceService;
        this.imageUsageChecker = imageUsageChecker;
        this.uploadRoot = Path.of(uploadPath).toAbsolutePath().normalize();
    }

    public void tryDelete(String imagePath) {
        if (!ImageReferenceService.isTrackable(imagePath)) {
            return;
        }
        String canonicalImagePath = ImageVariantService.canonicalPathForVariant(imagePath);
        if (imageReferenceService.hasReferences(canonicalImagePath)) {
            return;
        }
        if (imageUsageChecker.isUsed(canonicalImagePath)) {
            return;
        }
        if (!ImageReferenceService.isLocalImagePath(imagePath)) {
            return;
        }

        String relativePath = imagePath.replaceFirst("^/images/", "");
        Path file = uploadRoot.resolve(relativePath).normalize();
        if (!file.startsWith(uploadRoot)) {
            log.warn("Rejected image cleanup path outside upload root");
            return;
        }

        try {
            if (Files.isRegularFile(file)) {
                Files.delete(file);
                log.info("Deleted unreferenced image {}", imagePath);
                if (!ImageVariantService.isVariantPath(imagePath)) {
                    deleteVariantSiblings(file, imagePath);
                }
            }
        } catch (IOException e) {
            log.warn("Failed to delete unreferenced image {}", imagePath, e);
        }
    }

    private void deleteVariantSiblings(Path file, String imagePath) throws IOException {
        Path parent = file.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            return;
        }
        String glob = ImageVariantService.variantGlob(file.getFileName().toString());
        try (var variants = Files.newDirectoryStream(parent, glob)) {
            for (Path variant : variants) {
                if (Files.isRegularFile(variant)) {
                    Files.delete(variant);
                    log.info("Deleted unreferenced image variant {} for {}", variant.getFileName(), imagePath);
                }
            }
        }
    }
}
