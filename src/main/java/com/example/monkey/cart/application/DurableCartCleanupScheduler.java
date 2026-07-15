package com.example.monkey.cart.application;

import com.example.monkey.cart.domain.CartCleanupIntent;
import com.example.monkey.cart.domain.CartCleanupIntentStore;
import com.example.monkey.cart.domain.CartCleanupScheduler;
import com.example.monkey.cart.domain.CartItem;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class DurableCartCleanupScheduler implements CartCleanupScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(DurableCartCleanupScheduler.class);

    private final CartCleanupIntentStore intentStore;
    private final CartCleanupProcessor processor;

    public DurableCartCleanupScheduler(CartCleanupIntentStore intentStore, CartCleanupProcessor processor) {
        this.intentStore = intentStore;
        this.processor = processor;
    }

    @Override
    public void schedule(Long checkoutId, Long userId, List<CartItem> itemSnapshots, Duration cartTtl) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Cart cleanup must be scheduled inside the checkout transaction");
        }
        intentStore.save(CartCleanupIntent.pending(checkoutId, userId, itemSnapshots, cartTtl, LocalDateTime.now()));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    processor.process(checkoutId);
                } catch (RuntimeException exception) {
                    LOGGER.warn("Cart cleanup dispatch failed for checkout {}", checkoutId, exception);
                }
            }
        });
    }
}
