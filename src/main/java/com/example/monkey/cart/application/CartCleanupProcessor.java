package com.example.monkey.cart.application;

import com.example.monkey.cart.domain.CartCleanupIntent;
import com.example.monkey.cart.domain.CartCleanupIntentStatus;
import com.example.monkey.cart.domain.CartCleanupIntentStore;
import com.example.monkey.cart.domain.CartStore;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CartCleanupProcessor {

    private static final Duration RETRY_DELAY = Duration.ofMinutes(1);

    private final CartCleanupIntentStore intentStore;
    private final CartStore cartStore;
    private final TransactionTemplate requiresNew;
    private final TransactionTemplate withoutTransaction;
    private final Clock clock;
    private final Duration claimLease;

    @Autowired
    public CartCleanupProcessor(
            CartCleanupIntentStore intentStore, CartStore cartStore, PlatformTransactionManager transactionManager) {
        this(intentStore, cartStore, transactionManager, Clock.systemDefaultZone(), Duration.ofMinutes(1));
    }

    public CartCleanupProcessor(
            CartCleanupIntentStore intentStore,
            CartStore cartStore,
            PlatformTransactionManager transactionManager,
            Clock clock,
            Duration claimLease) {
        this.intentStore = intentStore;
        this.cartStore = cartStore;
        this.clock = clock;
        this.claimLease = claimLease;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.withoutTransaction = new TransactionTemplate(transactionManager);
        this.withoutTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    }

    public boolean process(Long checkoutId) {
        String claimToken = UUID.randomUUID().toString();
        LocalDateTime claimedAt = now();
        Optional<CartCleanupIntent> claimed = requiresNew.execute(
                status -> intentStore.claim(checkoutId, claimToken, claimedAt, claimedAt.plus(claimLease)));
        if (claimed == null || claimed.isEmpty()) {
            Boolean completed = requiresNew.execute(status -> intentStore
                    .findByCheckoutId(checkoutId)
                    .map(intent -> CartCleanupIntentStatus.COMPLETED.equals(intent.status()))
                    .orElse(false));
            return Boolean.TRUE.equals(completed);
        }
        try {
            withoutTransaction.executeWithoutResult(status -> cartStore.removeMatchingItems(
                    claimed.get().userId(),
                    claimed.get().itemSnapshots(),
                    claimed.get().cartTtl()));
        } catch (RuntimeException exception) {
            LocalDateTime failedAt = now();
            requiresNew.executeWithoutResult(status -> intentStore.failClaim(
                    checkoutId, claimToken, failedAt, failedAt.plus(RETRY_DELAY), failureMessage(exception)));
            return false;
        }
        LocalDateTime completedAt = now();
        Boolean completed =
                requiresNew.execute(status -> intentStore.completeClaim(checkoutId, claimToken, completedAt));
        return Boolean.TRUE.equals(completed);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), clock.getZone());
    }

    private static String failureMessage(RuntimeException exception) {
        String message = exception.getClass().getSimpleName() + ": " + String.valueOf(exception.getMessage());
        return message.length() <= 255 ? message : message.substring(0, 255);
    }
}
