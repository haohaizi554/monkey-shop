package com.example.monkey.shared.application.storage;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.example.monkey.shared.domain.storage.ImageReferenceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

class ImageReferenceTransactionsTest {

    @AfterEach
    void clearTransactionState() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void reservesBeforeDatabaseCommitAndCompensatesOnRollback() {
        ImageReferenceService references = mock();
        beginTransaction();

        ImageReferenceTransactions.retainBeforeWrite(references, "/images/product/new.png");

        verify(references).retain("/images/product/new.png");
        verify(references, never()).release("/images/product/new.png");
        TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        verify(references).release("/images/product/new.png");
    }

    @Test
    void committedReservationRemainsCounted() {
        ImageReferenceService references = mock();
        beginTransaction();

        ImageReferenceTransactions.retainBeforeWrite(references, "/images/product/new.png");
        TransactionSynchronizationUtils.triggerAfterCommit();
        TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_COMMITTED);

        verify(references).retain("/images/product/new.png");
        verify(references, never()).release("/images/product/new.png");
    }

    @Test
    void oldReferenceIsReleasedAndCleanedOnlyAfterCommit() {
        ImageReferenceService references = mock();
        ImageCleanupService cleanup = mock();
        beginTransaction();

        ImageReferenceTransactions.releaseAfterCommit(
                references, cleanup, "/images/product/old.png");

        verify(references, never()).release("/images/product/old.png");
        verify(cleanup, never()).tryDeleteCommitted("/images/product/old.png");
        TransactionSynchronizationUtils.triggerAfterCommit();
        verify(references).release("/images/product/old.png");
        verify(cleanup).tryDeleteCommitted("/images/product/old.png");
    }

    @Test
    void transactionWithoutSynchronizationFailsClosedAndCompensatesReservation() {
        ImageReferenceService references = mock();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThatThrownBy(() -> ImageReferenceTransactions.retainBeforeWrite(
                        references, "/images/product/new.png"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("synchronization");

        verify(references).retain("/images/product/new.png");
        verify(references).release("/images/product/new.png");
    }

    private static void beginTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }
}
