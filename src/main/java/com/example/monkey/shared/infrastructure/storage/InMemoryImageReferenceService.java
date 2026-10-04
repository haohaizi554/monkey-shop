package com.example.monkey.shared.infrastructure.storage;

import com.example.monkey.shared.domain.storage.ImageReferenceService;
import com.example.monkey.shared.domain.storage.ObjectStorageService;
import java.io.IOException;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.image-reference.provider", havingValue = "memory", matchIfMissing = true)
public class InMemoryImageReferenceService implements ImageReferenceService {

    private static final int DEFAULT_MAX_DELETION_TOMBSTONES = 100_000;
    private static final Duration DEFAULT_DELETION_TOMBSTONE_RETENTION = Duration.ofDays(7);

    private final Map<String, Long> counts = new HashMap<>();
    private final Map<String, DeletionMarker> deletionTombstones = new HashMap<>();
    private final Duration deletionClaimStaleAfter;
    private final Duration deletionTombstoneRetention;
    private final int maxDeletionTombstones;
    private final ObjectStorageService objectStorageService;
    private long version;
    private boolean authoritativeSnapshotReady;

    public InMemoryImageReferenceService() {
        this(Duration.ofMinutes(30), DEFAULT_DELETION_TOMBSTONE_RETENTION, DEFAULT_MAX_DELETION_TOMBSTONES);
    }

    InMemoryImageReferenceService(Duration deletionClaimStaleAfter) {
        this(deletionClaimStaleAfter, DEFAULT_DELETION_TOMBSTONE_RETENTION, DEFAULT_MAX_DELETION_TOMBSTONES);
    }

    public InMemoryImageReferenceService(
            @Value("${app.upload.cleanup.deletion-claim-stale-after:PT30M}") Duration deletionClaimStaleAfter,
            @Value("${app.upload.cleanup.deletion-tombstone-retention:P7D}") Duration deletionTombstoneRetention,
            @Value("${app.upload.cleanup.max-deletion-tombstones:100000}") int maxDeletionTombstones) {
        this(deletionClaimStaleAfter, deletionTombstoneRetention, maxDeletionTombstones, null);
    }

    @Autowired
    public InMemoryImageReferenceService(
            @Value("${app.upload.cleanup.deletion-claim-stale-after:PT30M}") Duration deletionClaimStaleAfter,
            @Value("${app.upload.cleanup.deletion-tombstone-retention:P7D}") Duration deletionTombstoneRetention,
            @Value("${app.upload.cleanup.max-deletion-tombstones:100000}") int maxDeletionTombstones,
            ObjectStorageService objectStorageService) {
        this.deletionClaimStaleAfter = Objects.requireNonNull(deletionClaimStaleAfter, "deletionClaimStaleAfter");
        this.deletionTombstoneRetention =
                Objects.requireNonNull(deletionTombstoneRetention, "deletionTombstoneRetention");
        this.maxDeletionTombstones = maxDeletionTombstones;
        this.objectStorageService = objectStorageService;
        if (deletionClaimStaleAfter.isNegative()) {
            throw new IllegalArgumentException("deletionClaimStaleAfter must not be negative");
        }
        if (deletionTombstoneRetention.isNegative()) {
            throw new IllegalArgumentException("deletionTombstoneRetention must not be negative");
        }
        if (maxDeletionTombstones <= 0) {
            throw new IllegalArgumentException("maxDeletionTombstones must be positive");
        }
    }

    @Override
    public synchronized void retain(String imagePath) {
        if (!ImageReferenceService.isTrackable(imagePath)) {
            return;
        }
        String canonicalPath = managedPath(imagePath, true);
        if (canonicalPath == null) {
            return;
        }
        if (deletionTombstones.containsKey(canonicalPath)) {
            throw new IllegalStateException("Image path has already been deleted and cannot be reused");
        }
        counts.merge(canonicalPath, 1L, Long::sum);
        version++;
    }

    @Override
    public synchronized void release(String imagePath) {
        if (!ImageReferenceService.isTrackable(imagePath)) {
            return;
        }
        String canonicalPath = managedPath(imagePath, false);
        if (canonicalPath == null) {
            return;
        }
        counts.computeIfPresent(canonicalPath, (ignored, count) -> count <= 1L ? null : count - 1L);
        version++;
    }

    @Override
    public synchronized long referenceCount(String imagePath) {
        String canonicalPath = managedPath(imagePath, false);
        return canonicalPath == null ? 0L : Math.max(0L, counts.getOrDefault(canonicalPath, 0L));
    }

    @Override
    public synchronized void clear() {
        counts.clear();
        authoritativeSnapshotReady = false;
        version++;
    }

    @Override
    public synchronized void replace(Collection<String> imagePaths) {
        Map<String, Long> replacement = new HashMap<>();
        if (imagePaths != null) {
            imagePaths.stream()
                    .filter(ImageReferenceService::isTrackable)
                    .map(path -> managedPath(path, false))
                    .filter(Objects::nonNull)
                    .forEach(path -> {
                        if (deletionTombstones.containsKey(path)) {
                            throw new IllegalStateException(
                                    "Image snapshot contains a deleted path and cannot be published");
                        }
                        replacement.merge(path, 1L, Long::sum);
                    });
        }
        counts.clear();
        counts.putAll(replacement);
        authoritativeSnapshotReady = true;
        version++;
    }

    @Override
    public synchronized long snapshotVersion() {
        return version;
    }

    @Override
    public synchronized boolean replaceIfUnchanged(Collection<String> imagePaths, long expectedVersion) {
        if (version != expectedVersion) {
            return false;
        }
        replace(imagePaths);
        return true;
    }

    @Override
    public synchronized boolean authoritativeSnapshotReady() {
        return authoritativeSnapshotReady;
    }

    @Override
    public boolean deleteIfUnreferenced(String imagePath, Supplier<Boolean> physicalDeletion) {
        String canonicalPath = managedPath(imagePath, false);
        if (canonicalPath == null) {
            return false;
        }
        String claimToken = UUID.randomUUID().toString();
        long claimedAt = System.currentTimeMillis();
        synchronized (this) {
            DeletionMarker existingMarker = deletionTombstones.get(canonicalPath);
            if (!authoritativeSnapshotReady
                    || !ImageReferenceService.isTrackable(canonicalPath)
                    || counts.getOrDefault(canonicalPath, 0L) > 0L
                    || existingMarker != null && existingMarker.state() == DeletionState.DELETED
                    || existingMarker != null
                            && existingMarker.state() == DeletionState.CLAIMED
                            && claimedAt - existingMarker.changedAtMillis() < deletionClaimStaleAfter.toMillis()) {
                return false;
            }
            if (existingMarker == null && deletionTombstones.size() >= maxDeletionTombstones) {
                throw new IllegalStateException("Image deletion tombstone capacity is exhausted; cleanup is disabled");
            }
            deletionTombstones.put(canonicalPath, new DeletionMarker(DeletionState.CLAIMED, claimToken, claimedAt));
            version++;
        }

        boolean deleted = false;
        try {
            deleted = Boolean.TRUE.equals(physicalDeletion.get());
            return deleted;
        } finally {
            completeDeletion(canonicalPath, claimToken, deleted);
        }
    }

    @Override
    public synchronized DeletionMaintenanceResult maintainDeletionState(
            Collection<String> authoritativeReferences,
            Collection<String> existingStorageReferences,
            long nowEpochMillis) {
        if (!authoritativeSnapshotReady) {
            return DeletionMaintenanceResult.NONE;
        }
        Set<String> referenced = canonicalPaths(authoritativeReferences);
        Set<String> existing = canonicalPaths(existingStorageReferences);
        int recoveredClaims = 0;
        int markedDeleted = 0;
        int compactedTombstones = 0;
        Iterator<Map.Entry<String, DeletionMarker>> iterator =
                deletionTombstones.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, DeletionMarker> entry = iterator.next();
            String path = entry.getKey();
            DeletionMarker marker = entry.getValue();
            if (referenced.contains(path)) {
                continue;
            }
            if (existing.contains(path)) {
                if (marker.state() == DeletionState.DELETED) {
                    entry.setValue(new DeletionMarker(DeletionState.RETRYABLE, marker.claimToken(), nowEpochMillis));
                    version++;
                }
                continue;
            }
            if (marker.state() == DeletionState.CLAIMED
                    && nowEpochMillis - marker.changedAtMillis() >= deletionClaimStaleAfter.toMillis()) {
                entry.setValue(new DeletionMarker(DeletionState.DELETED, marker.claimToken(), nowEpochMillis));
                recoveredClaims++;
                markedDeleted++;
                version++;
                continue;
            }
            if (marker.state() == DeletionState.RETRYABLE) {
                entry.setValue(new DeletionMarker(DeletionState.DELETED, marker.claimToken(), nowEpochMillis));
                markedDeleted++;
                version++;
                continue;
            }
            if (marker.state() == DeletionState.DELETED
                    && nowEpochMillis - marker.changedAtMillis() >= deletionTombstoneRetention.toMillis()) {
                iterator.remove();
                compactedTombstones++;
                version++;
            }
        }
        return new DeletionMaintenanceResult(recoveredClaims, markedDeleted, compactedTombstones);
    }

    private synchronized void completeDeletion(String canonicalPath, String claimToken, boolean deleted) {
        DeletionMarker currentMarker = deletionTombstones.get(canonicalPath);
        if (currentMarker == null
                || currentMarker.state() != DeletionState.CLAIMED
                || !currentMarker.claimToken().equals(claimToken)) {
            return;
        }
        deletionTombstones.put(
                canonicalPath,
                new DeletionMarker(
                        deleted ? DeletionState.DELETED : DeletionState.RETRYABLE,
                        claimToken,
                        System.currentTimeMillis()));
        version++;
    }

    private Set<String> canonicalPaths(Collection<String> imagePaths) {
        Set<String> paths = new HashSet<>();
        if (imagePaths == null) {
            return paths;
        }
        imagePaths.stream()
                .filter(ImageReferenceService::isTrackable)
                .map(path -> managedPath(path, false))
                .filter(Objects::nonNull)
                .forEach(paths::add);
        return paths;
    }

    private String managedPath(String imageReference, boolean requireExisting) {
        if (objectStorageService == null) {
            return ImageReferenceService.canonicalPath(imageReference);
        }
        String objectKey;
        try {
            objectKey = objectStorageService.resolveObjectKey(imageReference);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Unable to resolve managed image reference", failure);
        }
        if (objectKey == null || objectKey.isBlank()) {
            return null;
        }
        if (requireExisting) {
            try {
                if (!objectStorageService.exists(objectKey)) {
                    throw new IllegalStateException("Managed image object does not exist");
                }
            } catch (IOException failure) {
                throw new IllegalStateException("Unable to verify managed image object", failure);
            }
        }
        return ImageReferenceService.canonicalPath(objectKey.trim());
    }

    private enum DeletionState {
        CLAIMED,
        RETRYABLE,
        DELETED
    }

    private record DeletionMarker(DeletionState state, String claimToken, long changedAtMillis) {}
}
