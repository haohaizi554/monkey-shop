package com.example.monkey.shared.application.storage;

import com.example.monkey.shared.domain.storage.ImageReferenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Coordinates non-transactional reference state with the surrounding database transaction. */
public final class ImageReferenceTransactions {

    private static final Logger log = LoggerFactory.getLogger(ImageReferenceTransactions.class);

    private ImageReferenceTransactions() {}

    /**
     * Reserves an image before the database write becomes visible. A rollback releases that conservative
     * reservation; a process failure can only leak a count, which the authoritative rebuild repairs.
     */
    public static void retainBeforeWrite(ImageReferenceService references, String imagePath) {
        references.retain(imagePath);
        if (!ImageReferenceService.isTrackable(imagePath)
                || !TransactionSynchronizationManager.isActualTransactionActive()) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            compensateRetain(references, imagePath);
            throw new IllegalStateException("Image reference transaction synchronization is unavailable");
        }
        try {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        compensateRetain(references, imagePath);
                    }
                }
            });
        } catch (RuntimeException registrationFailure) {
            compensateRetain(references, imagePath);
            throw registrationFailure;
        }
    }

    /** Releases an old image only after the database no longer references it. */
    public static void releaseAfterCommit(
            ImageReferenceService references, ImageCleanupService cleanup, String imagePath) {
        if (!ImageReferenceService.isTrackable(imagePath)) {
            return;
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            releaseAndClean(references, cleanup, imagePath);
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Image reference transaction synchronization is unavailable");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                releaseAndClean(references, cleanup, imagePath);
            }
        });
    }

    private static void compensateRetain(ImageReferenceService references, String imagePath) {
        try {
            references.release(imagePath);
        } catch (RuntimeException compensationFailure) {
            log.error("Unable to compensate an image reference after transaction rollback", compensationFailure);
        }
    }

    private static void releaseAndClean(
            ImageReferenceService references, ImageCleanupService cleanup, String imagePath) {
        try {
            references.release(imagePath);
            cleanup.tryDeleteCommitted(imagePath);
        } catch (RuntimeException releaseFailure) {
            // The database is already committed. Keep the physical file and let the authoritative rebuild
            // repair conservative counts instead of surfacing a misleading transaction failure.
            log.error("Unable to release an image reference after database commit", releaseFailure);
        }
    }
}
