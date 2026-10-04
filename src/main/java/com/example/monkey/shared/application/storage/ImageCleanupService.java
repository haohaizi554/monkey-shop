package com.example.monkey.shared.application.storage;

import com.example.monkey.shared.domain.storage.ImageReferenceService;
import com.example.monkey.shared.domain.storage.ImageUsageChecker;
import com.example.monkey.shared.domain.storage.ObjectStorageService;
import com.example.monkey.shared.infrastructure.storage.LocalObjectStorageService;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class ImageCleanupService {

    private static final Logger log = LoggerFactory.getLogger(ImageCleanupService.class);

    private final ImageReferenceService imageReferenceService;
    private final ImageUsageChecker imageUsageChecker;
    private final ObjectStorageService objectStorageService;
    private final ImageVariantService imageVariantService;

    @Autowired
    public ImageCleanupService(
            ImageReferenceService imageReferenceService,
            ImageUsageChecker imageUsageChecker,
            ObjectStorageService objectStorageService,
            ImageVariantService imageVariantService,
            @Value("${app.storage.provider:local}") String storageProvider,
            @Value("${app.upload.path:uploads/images}") String uploadPath) {
        this(imageReferenceService, imageUsageChecker, objectStorageService, imageVariantService);
    }

    ImageCleanupService(
            ImageReferenceService imageReferenceService,
            ImageUsageChecker imageUsageChecker,
            ObjectStorageService objectStorageService,
            ImageVariantService imageVariantService) {
        this.imageReferenceService = imageReferenceService;
        this.imageUsageChecker = imageUsageChecker;
        this.objectStorageService = objectStorageService;
        this.imageVariantService = imageVariantService;
    }

    public ImageCleanupService(
            ImageReferenceService imageReferenceService,
            ImageUsageChecker imageUsageChecker,
            String uploadPath) {
        this(imageReferenceService, imageUsageChecker, localCleanupComponents(uploadPath));
    }

    private ImageCleanupService(
            ImageReferenceService imageReferenceService,
            ImageUsageChecker imageUsageChecker,
            LocalCleanupComponents components) {
        this(imageReferenceService, imageUsageChecker, components.objectStorageService(), components.variantService());
    }

    public void tryDelete(String imagePath) {
        if (!ImageReferenceService.isTrackable(imagePath)) {
            return;
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            if (!TransactionSynchronizationManager.isSynchronizationActive()) {
                log.warn("Skipped image cleanup because transaction completion synchronization is unavailable");
                return;
            }
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    tryDeleteCommitted(imagePath);
                }
            });
            return;
        }
        tryDeleteCommitted(imagePath);
    }

    /** Executes cleanup only after the caller's database mutation is durably committed. */
    public void tryDeleteCommitted(String imagePath) {
        if (!ImageReferenceService.isTrackable(imagePath)) {
            return;
        }
        String canonicalImagePath = ImageVariantService.canonicalPathForVariant(imagePath);
        String canonicalObjectKey = resolveCanonicalObjectKey(imagePath);
        if (canonicalObjectKey == null || imageVariantService == null) {
            return;
        }
        String providerCanonicalReference = resolveProviderCanonicalReference(
                canonicalObjectKey, canonicalImagePath);
        if (providerCanonicalReference == null) {
            return;
        }
        if (imageReferenceService.hasReferences(canonicalImagePath)
                || !providerCanonicalReference.equals(canonicalImagePath)
                        && imageReferenceService.hasReferences(providerCanonicalReference)) {
            return;
        }
        if (imageUsageChecker.isUsed(canonicalImagePath)
                || !providerCanonicalReference.equals(canonicalImagePath)
                        && imageUsageChecker.isUsed(providerCanonicalReference)) {
            return;
        }
        try {
            imageReferenceService.deleteIfUnreferenced(
                    canonicalImagePath, () -> deleteCanonicalAndVariants(canonicalObjectKey, canonicalImagePath));
        } catch (UnsupportedOperationException unsupported) {
            log.warn("Skipped image cleanup because the reference provider cannot claim deletions");
        }
    }

    private String resolveCanonicalObjectKey(String imagePath) {
        if (objectStorageService == null) {
            return null;
        }
        try {
            String objectKey = objectStorageService.resolveObjectKey(imagePath);
            if (objectKey == null || objectKey.isBlank()) {
                return null;
            }
            return ImageVariantService.canonicalPathForVariant(objectKey.trim());
        } catch (RuntimeException resolutionFailure) {
            log.warn("Skipped image cleanup because the object reference could not be resolved", resolutionFailure);
            return null;
        }
    }

    private String resolveProviderCanonicalReference(String canonicalObjectKey, String fallbackReference) {
        try {
            String publicReference = objectStorageService.publicUrl(canonicalObjectKey);
            if (!ImageReferenceService.isTrackable(publicReference)) {
                return fallbackReference;
            }
            return ImageVariantService.canonicalPathForVariant(publicReference.trim());
        } catch (RuntimeException resolutionFailure) {
            log.warn("Skipped image cleanup because the provider public reference could not be resolved", resolutionFailure);
            return null;
        }
    }

    private boolean deleteCanonicalAndVariants(String objectKey, String imagePath) {
        try {
            boolean canonicalDeleted = objectStorageService.delete(objectKey);
            imageVariantService.deleteVariants(objectKey);
            if (!canonicalDeleted) {
                log.warn("Failed to delete unreferenced object-storage image {}", imagePath);
                return false;
            }
            log.info("Deleted unreferenced object-storage image {}", imagePath);
            return true;
        } catch (IOException | UnsupportedOperationException exception) {
            log.warn("Failed to fully delete unreferenced object-storage image {}", imagePath, exception);
            return false;
        }
    }

    private static LocalCleanupComponents localCleanupComponents(String uploadPath) {
        ObjectStorageService localStorage = new LocalObjectStorageService(uploadPath, "");
        ImageVariantService variants = new ImageVariantService(localStorage, true, "webp,avif", "320,640", false);
        return new LocalCleanupComponents(localStorage, variants);
    }

    private record LocalCleanupComponents(
            ObjectStorageService objectStorageService, ImageVariantService variantService) {}
}
