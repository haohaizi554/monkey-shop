package com.example.monkey.shared.domain.storage;

import java.util.Collection;
import java.util.Locale;
import java.util.function.Supplier;

public interface ImageReferenceService {

    void retain(String imagePath);

    void release(String imagePath);

    long referenceCount(String imagePath);

    default boolean hasReferences(String imagePath) {
        return referenceCount(imagePath) > 0;
    }

    void clear();

    /**
     * Publishes a complete reference snapshot. Implementations that cannot replace their live state
     * without first destroying it must leave this operation unsupported so callers disable physical
     * deletion and preserve the last-known-good provider state.
     */
    default void replace(Collection<String> imagePaths) {
        throw new UnsupportedOperationException("Atomic image reference replacement is unavailable");
    }

    /**
     * Returns a monotonically changing provider revision used to detect reference mutations while a
     * complete persisted-reference snapshot is being assembled.
     */
    default long snapshotVersion() {
        return 0L;
    }

    /**
     * Publishes a complete snapshot only when the provider has not changed since {@code expectedVersion}
     * was observed. Returning {@code false} must leave the live reference state untouched.
     */
    default boolean replaceIfUnchanged(Collection<String> imagePaths, long expectedVersion) {
        throw new UnsupportedOperationException("Compare-and-swap image reference replacement is unavailable");
    }

    /**
     * Reports whether this provider has successfully published at least one complete authoritative
     * reference snapshot. Physical deletion must fail closed while this is {@code false}; retain and
     * release remain available so writes that happen while the first snapshot is assembled are included
     * in the snapshot CAS revision.
     */
    default boolean authoritativeSnapshotReady() {
        return false;
    }

    /**
     * Compatibility fence for short provider mutations. Physical deletion must use the tokenized
     * per-path claim contract below; a renewable process lock is not a deletion fencing token and must
     * never be held across storage I/O.
     */
    default <T> T withMutationFence(Supplier<T> work) {
        synchronized (this) {
            return work.get();
        }
    }

    /**
     * Establishes a durable, canonical-path deletion tombstone only when the current reference count
     * is zero, validates ownership of that tombstone, and then invokes the physical deletion action.
     * Retain operations must reject a path once its tombstone exists. Implementations that cannot
     * provide that atomic contract must leave this operation unsupported so cleanup fails closed.
     *
     * <p>The tombstone intentionally survives a failed or interrupted physical delete. Providers may
     * mark a completed call retryable, but retain must remain rejected throughout every retry. Image
     * object keys are immutable UUIDs, so a successful tombstone never permits same-path reuse.
     */
    default boolean deleteIfUnreferenced(String imagePath, Supplier<Boolean> physicalDeletion) {
        throw new UnsupportedOperationException("Atomic image deletion claims are unavailable");
    }

    /**
     * Reconciles deletion claims only after the caller has completed both an authoritative persisted
     * reference scan and a complete storage-object listing. Implementations must use token/value CAS for
     * every state change and may compact only completed tombstones whose object and reference are both
     * absent. Incomplete discovery must not call this method. Distributed providers must use their
     * authoritative server clock for lease and retention decisions; {@code nowEpochMillis} is a
     * single-process/test clock hint and must not override a safer provider clock.
     */
    default DeletionMaintenanceResult maintainDeletionState(
            Collection<String> authoritativeReferences,
            Collection<String> existingStorageReferences,
            long nowEpochMillis) {
        return DeletionMaintenanceResult.NONE;
    }

    default void rebuild(Collection<String> imagePaths) {
        replace(imagePaths);
    }

    record DeletionMaintenanceResult(int recoveredClaims, int markedDeleted, int compactedTombstones) {

        public static final DeletionMaintenanceResult NONE = new DeletionMaintenanceResult(0, 0, 0);
    }

    static boolean isTrackable(String imagePath) {
        return hasText(imagePath) && !imagePath.contains("default_");
    }

    static boolean isLocalImagePath(String imagePath) {
        return hasText(imagePath) && imagePath.startsWith("/images/");
    }

    /** Maps a generated variant URL to the immutable logical asset path used by every provider key. */
    static String canonicalPath(String imagePath) {
        if (!hasText(imagePath)) {
            return imagePath;
        }
        int marker = imagePath.lastIndexOf('@');
        if (marker < 0) {
            return imagePath;
        }
        String canonical = imagePath.substring(0, marker);
        String lower = canonical.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return canonical;
        }
        return imagePath;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
