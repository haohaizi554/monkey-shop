package com.example.monkey.shared.infrastructure.storage;

import com.example.monkey.shared.application.storage.ImageVariantService;
import com.example.monkey.shared.application.tenant.ActiveTenantIterator;
import com.example.monkey.shared.domain.storage.ImageReferenceService;
import com.example.monkey.shared.domain.storage.ObjectStorageService;
import com.example.monkey.shared.domain.storage.StoredImageReferenceReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ImageTask {

    private static final Logger log = LoggerFactory.getLogger(ImageTask.class);

    private final StoredImageReferenceReader storedImageReferenceReader;
    private final ImageReferenceService imageReferenceService;
    private final ObjectStorageService objectStorageService;
    private final ActiveTenantIterator activeTenantIterator;
    private final Duration gracePeriod;

    /** Compatibility constructor for direct local callers and existing tests. */
    public ImageTask(
            StoredImageReferenceReader storedImageReferenceReader,
            ImageReferenceService imageReferenceService,
            @Value("${app.upload.path:uploads/images}") String uploadPath,
            @Value("${app.upload.cleanup.grace-period:PT24H}") Duration gracePeriod) {
        this(
                storedImageReferenceReader,
                imageReferenceService,
                new LocalObjectStorageService(uploadPath, ""),
                null,
                gracePeriod);
    }

    /** Compatibility constructor for direct local callers that provide retained-tenant iteration. */
    public ImageTask(
            StoredImageReferenceReader storedImageReferenceReader,
            ImageReferenceService imageReferenceService,
            ActiveTenantIterator activeTenantIterator,
            @Value("${app.upload.path:uploads/images}") String uploadPath,
            @Value("${app.upload.cleanup.grace-period:PT24H}") Duration gracePeriod) {
        this(
                storedImageReferenceReader,
                imageReferenceService,
                new LocalObjectStorageService(uploadPath, ""),
                activeTenantIterator,
                gracePeriod);
    }

    /** Compatibility constructor for direct provider callers without retained-tenant iteration. */
    public ImageTask(
            StoredImageReferenceReader storedImageReferenceReader,
            ImageReferenceService imageReferenceService,
            ObjectStorageService objectStorageService,
            @Value("${app.upload.cleanup.grace-period:PT24H}") Duration gracePeriod) {
        this(storedImageReferenceReader, imageReferenceService, objectStorageService, null, gracePeriod);
    }

    /** Compatibility constructor for callers that order the tenant iterator before the storage provider. */
    public ImageTask(
            StoredImageReferenceReader storedImageReferenceReader,
            ImageReferenceService imageReferenceService,
            ActiveTenantIterator activeTenantIterator,
            ObjectStorageService objectStorageService,
            @Value("${app.upload.cleanup.grace-period:PT24H}") Duration gracePeriod) {
        this(
                storedImageReferenceReader,
                imageReferenceService,
                objectStorageService,
                activeTenantIterator,
                gracePeriod);
    }

    @Autowired
    public ImageTask(
            StoredImageReferenceReader storedImageReferenceReader,
            ImageReferenceService imageReferenceService,
            ObjectStorageService objectStorageService,
            ActiveTenantIterator activeTenantIterator,
            @Value("${app.upload.cleanup.grace-period:PT24H}") Duration gracePeriod) {
        this.storedImageReferenceReader =
                Objects.requireNonNull(storedImageReferenceReader, "storedImageReferenceReader");
        this.imageReferenceService = Objects.requireNonNull(imageReferenceService, "imageReferenceService");
        this.objectStorageService = Objects.requireNonNull(objectStorageService, "objectStorageService");
        this.activeTenantIterator = activeTenantIterator;
        this.gracePeriod = Objects.requireNonNull(gracePeriod, "gracePeriod");
        if (gracePeriod.isNegative()) {
            throw new IllegalArgumentException("gracePeriod must not be negative");
        }
    }

    @Scheduled(cron = "0 0 3 * * ?")
    @SchedulerLock(
            name = "monkeyshop.image.cleanup",
            lockAtMostFor = "${app.upload.cleanup.lock-at-most-for:PT30M}",
            lockAtLeastFor = "${app.upload.cleanup.lock-at-least-for:PT1M}")
    public void cleanUpOrphanImages() {
        log.info("Starting orphan image cleanup");

        long expectedReferenceVersion = imageReferenceService.snapshotVersion();
        List<String> discoveredReferences = discoverReferences();

        // Discover every candidate and its metadata before deleting anything. A provider listing failure
        // therefore cannot replace the last-known-good reference state or leave a partial object set processed.
        List<CleanupCandidate> candidates = discoverCandidates();

        // A reference may have appeared while provider objects were listed. Keep both successful observations
        // so a transiently missing row can only defer deletion. Provider version validation below also rejects
        // the entire run when any application instance retained or released a reference during discovery.
        List<String> confirmedReferences = discoverReferences();
        List<String> completeReferenceSnapshot = mergeReferenceSnapshots(discoveredReferences, confirmedReferences);

        Instant now = Instant.now();
        List<String> finalReferenceSnapshot = completeReferenceSnapshot;
        Set<String> referencedPaths = Set.copyOf(finalReferenceSnapshot);
        if (!publishReferenceSnapshot(finalReferenceSnapshot, expectedReferenceVersion)) {
            log.info("Skipped orphan image deletion because references changed during discovery");
            return;
        }
        for (CleanupCandidate candidate : candidates) {
            // Read every live count before claiming any deletion. A provider read failure aborts the
            // complete run without partially deleting the candidate set. The per-path deletion claim,
            // rather than a long-lived global lock, is the final retain-versus-delete safety boundary.
            imageReferenceService.hasReferences(candidate.canonicalPath());
        }
        List<String> existingStorageReferences = candidates.stream()
                .map(CleanupCandidate::canonicalPath)
                .distinct()
                .toList();
        imageReferenceService.maintainDeletionState(
                finalReferenceSnapshot, existingStorageReferences, now.toEpochMilli());

        Map<String, List<CleanupCandidate>> candidatesByCanonicalPath = new LinkedHashMap<>();
        for (CleanupCandidate candidate : candidates) {
            candidatesByCanonicalPath
                    .computeIfAbsent(candidate.canonicalPath(), ignored -> new ArrayList<>())
                    .add(candidate);
        }
        for (Map.Entry<String, List<CleanupCandidate>> entry : candidatesByCanonicalPath.entrySet()) {
            String canonicalPath = entry.getKey();
            List<CleanupCandidate> logicalAssetFiles = entry.getValue();
            if (referencedPaths.contains(canonicalPath)
                    || logicalAssetFiles.stream()
                            .anyMatch(candidate ->
                                    !candidate.lastModified().plus(gracePeriod).isBefore(now))) {
                continue;
            }
            try {
                imageReferenceService.deleteIfUnreferenced(
                        canonicalPath, () -> deleteLogicalAsset(canonicalPath, logicalAssetFiles));
            } catch (UnsupportedOperationException unsupported) {
                log.warn("Skipped orphan image deletion because the reference provider cannot claim deletions");
                break;
            }
        }
        log.info("Finished orphan image cleanup");
    }

    private boolean deleteLogicalAsset(String canonicalPath, List<CleanupCandidate> candidates) {
        try {
            for (CleanupCandidate candidate : candidates) {
                if (!objectStorageService.delete(candidate.objectKey())) {
                    throw new IllegalStateException(
                            "Object storage did not confirm deletion of " + candidate.objectKey());
                }
                log.info("Deleted orphan image {}", candidate.publicUrl());
            }
            return true;
        } catch (IOException exception) {
            // The provider keeps the tombstone after any partial failure, so the immutable canonical path
            // cannot be reused for a database reference that may now be missing one or more files.
            throw new UncheckedIOException("Unable to fully delete image asset " + canonicalPath, exception);
        }
    }

    private static List<String> mergeReferenceSnapshots(
            Collection<String> discoveredReferences, Collection<String> confirmedReferences) {
        Map<String, Integer> maximumCounts = referenceCounts(discoveredReferences);
        referenceCounts(confirmedReferences).forEach((path, count) -> maximumCounts.merge(path, count, Math::max));
        List<String> mergedReferences = new ArrayList<>();
        maximumCounts.forEach((path, count) -> {
            for (int occurrence = 0; occurrence < count; occurrence++) {
                mergedReferences.add(path);
            }
        });
        return List.copyOf(mergedReferences);
    }

    private static Map<String, Integer> referenceCounts(Collection<String> imagePaths) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        imagePaths.forEach(path -> counts.merge(path, 1, Integer::sum));
        return counts;
    }

    private List<String> discoverReferences() {
        List<String> discoveredReferences = new ArrayList<>();
        if (activeTenantIterator == null) {
            collectReferences(discoveredReferences);
        } else {
            ActiveTenantIterator.IterationResult result = activeTenantIterator.forEachRetainedTenant(tenantId -> {
                collectReferences(discoveredReferences);
                return 0L;
            });
            if (!result.failedTenantIds().isEmpty()) {
                throw new IllegalStateException(
                        "Image reference discovery failed for retained tenants " + result.failedTenantIds());
            }
        }
        return discoveredReferences;
    }

    private void collectReferences(List<String> discoveredReferences) {
        Consumer<String> collect = imagePath -> {
            if (!ImageReferenceService.isTrackable(imagePath)) {
                return;
            }
            String objectKey = objectStorageService.resolveObjectKey(imagePath.trim());
            if (objectKey == null) {
                return;
            }
            String publicUrl = objectStorageService.publicUrl(objectKey);
            String canonicalPath = ImageVariantService.canonicalPathForVariant(publicUrl);
            if (ImageReferenceService.isTrackable(canonicalPath)) {
                discoveredReferences.add(canonicalPath);
            }
        };
        storedImageReferenceReader.forEachReferencedImagePath(collect);
    }

    private boolean publishReferenceSnapshot(Collection<String> discoveredReferences, long expectedReferenceVersion) {
        try {
            return imageReferenceService.replaceIfUnchanged(discoveredReferences, expectedReferenceVersion);
        } catch (UnsupportedOperationException unsupported) {
            // A complete in-memory snapshot is not enough to prove that a provider's live state is safe to
            // mutate. In particular, do not clear a last-known-good provider state when CAS is unavailable.
            log.warn("Image reference provider does not support atomic replacement; cleanup is disabled");
            return false;
        }
    }

    private List<CleanupCandidate> discoverCandidates() {
        List<ObjectStorageService.StoredObjectMetadata> storedObjects;
        try {
            storedObjects = objectStorageService.listStoredObjects();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to list stored image objects", exception);
        }
        if (storedObjects == null) {
            throw new IllegalStateException("Object storage returned no image object listing");
        }
        List<CleanupCandidate> candidates = new ArrayList<>();
        for (ObjectStorageService.StoredObjectMetadata storedObject : storedObjects) {
            if (storedObject == null
                    || storedObject.objectKey() == null
                    || storedObject.objectKey().isBlank()
                    || storedObject.lastModified() == null) {
                throw new IllegalStateException("Object storage returned incomplete image metadata");
            }
            String objectKey = objectStorageService.resolveObjectKey(storedObject.objectKey());
            if (objectKey == null) {
                throw new IllegalStateException("Object storage returned an unmanaged image object key");
            }
            if (isDefaultObject(objectKey)) {
                continue;
            }
            String publicUrl = objectStorageService.publicUrl(objectKey);
            String canonicalPath = ImageVariantService.canonicalPathForVariant(publicUrl);
            if (!ImageReferenceService.isTrackable(canonicalPath)) {
                continue;
            }
            candidates.add(new CleanupCandidate(objectKey, publicUrl, canonicalPath, storedObject.lastModified()));
        }
        return List.copyOf(candidates);
    }

    private static boolean isDefaultObject(String objectKey) {
        int slash = objectKey.lastIndexOf('/');
        return objectKey.substring(slash + 1).contains("default_");
    }

    private record CleanupCandidate(String objectKey, String publicUrl, String canonicalPath, Instant lastModified) {}
}
