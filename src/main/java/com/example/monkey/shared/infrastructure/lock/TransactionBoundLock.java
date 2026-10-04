package com.example.monkey.shared.infrastructure.lock;

import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.redisson.api.RLock;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Holds a Redisson lock until the surrounding transaction completes.
 *
 * <p>The lease is Redisson's watchdog, not a fixed timeout. Unlocking inside the business callback
 * would publish the critical section before the database commit.
 */
public final class TransactionBoundLock {

    private TransactionBoundLock() {}

    public static <T> T call(
            RLock lock,
            Duration wait,
            Supplier<T> action,
            String busyMessage,
            String interruptedMessage,
            String unavailableMessage) {
        boolean acquired = false;
        try {
            acquired = lock.tryLock(wait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, interruptedMessage, exception);
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, unavailableMessage, exception);
        }
        if (!acquired) {
            throw new BusinessException(ErrorCode.CONFLICT, busyMessage);
        }
        boolean releaseAfterTransaction = false;
        try {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                RLock held = lock;
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        unlock(held);
                    }
                });
                releaseAfterTransaction = true;
            }
            return action.get();
        } finally {
            if (!releaseAfterTransaction) {
                unlock(lock);
            }
        }
    }

    private static void unlock(RLock lock) {
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (RuntimeException ignored) {
            // The business outcome is already decided. An unlock failure must not replace it.
        }
    }
}
