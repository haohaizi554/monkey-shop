package com.example.monkey.cart.application;

import com.example.monkey.cart.domain.CartCleanupIntent;
import com.example.monkey.cart.domain.CartCleanupIntentStatus;
import com.example.monkey.cart.domain.CartCleanupIntentStore;
import com.example.monkey.cart.domain.CartStore;
import java.time.Duration;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CartCleanupProcessor {

    private static final Duration RETRY_DELAY = Duration.ofMinutes(1);

    private final CartCleanupIntentStore intentStore;
    private final CartStore cartStore;
    private final TransactionTemplate transactionTemplate;

    public CartCleanupProcessor(
            CartCleanupIntentStore intentStore, CartStore cartStore, PlatformTransactionManager transactionManager) {
        this.intentStore = intentStore;
        this.cartStore = cartStore;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public boolean process(Long checkoutId) {
        Boolean result = transactionTemplate.execute(status -> processInTransaction(checkoutId));
        return Boolean.TRUE.equals(result);
    }

    private boolean processInTransaction(Long checkoutId) {
        CartCleanupIntent intent = intentStore.findByCheckoutId(checkoutId).orElse(null);
        if (intent == null) {
            return false;
        }
        if (CartCleanupIntentStatus.COMPLETED.equals(intent.status())) {
            return true;
        }
        LocalDateTime now = LocalDateTime.now();
        try {
            cartStore.removeItems(intent.userId(), intent.skuIds(), intent.cartTtl());
            intentStore.save(intent.completed(now));
            return true;
        } catch (RuntimeException exception) {
            intentStore.save(intent.failed(now, RETRY_DELAY, failureMessage(exception)));
            return false;
        }
    }

    private static String failureMessage(RuntimeException exception) {
        String message = exception.getClass().getSimpleName() + ": " + String.valueOf(exception.getMessage());
        return message.length() <= 255 ? message : message.substring(0, 255);
    }
}
