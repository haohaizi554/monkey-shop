package com.example.monkey.cart.application;

import com.example.monkey.cart.domain.CartCleanupIntentStore;
import com.example.monkey.cart.domain.CartCleanupTenantSource;
import com.example.monkey.shared.application.tenant.TenantContext;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CartCleanupRetryWorker {

    private static final int TENANT_BATCH_SIZE = 100;
    private static final Logger LOGGER = LoggerFactory.getLogger(CartCleanupRetryWorker.class);

    private final CartCleanupIntentStore intentStore;
    private final CartCleanupTenantSource tenantSource;
    private final CartCleanupProcessor processor;

    public CartCleanupRetryWorker(
            CartCleanupIntentStore intentStore, CartCleanupTenantSource tenantSource, CartCleanupProcessor processor) {
        this.intentStore = intentStore;
        this.tenantSource = tenantSource;
        this.processor = processor;
    }

    @Scheduled(fixedDelayString = "${app.cart.cleanup.retry-delay-ms:30000}")
    public void retryPending() {
        LocalDateTime cutoff = LocalDateTime.now();
        Long previousTenantId = TenantContext.currentTenantId().orElse(null);
        long afterTenantId = 0L;
        try {
            while (true) {
                List<Long> tenantIds =
                        tenantSource.findTenantIdsWithReadyIntents(cutoff, afterTenantId, TENANT_BATCH_SIZE);
                if (tenantIds.isEmpty()) {
                    return;
                }
                long nextAfterTenantId = retryTenants(tenantIds, cutoff, afterTenantId, previousTenantId);
                if (nextAfterTenantId <= afterTenantId) {
                    LOGGER.error("Cart cleanup tenant page did not advance after tenant {}", afterTenantId);
                    return;
                }
                afterTenantId = nextAfterTenantId;
            }
        } finally {
            restoreTenantContext(previousTenantId);
        }
    }

    private long retryTenants(List<Long> tenantIds, LocalDateTime cutoff, long afterTenantId, Long previousTenantId) {
        long nextAfterTenantId = afterTenantId;
        for (Long tenantId : tenantIds) {
            if (tenantId == null || tenantId <= nextAfterTenantId) {
                continue;
            }
            try {
                TenantContext.setTenantId(tenantId);
                retryTenant(cutoff);
            } catch (RuntimeException exception) {
                LOGGER.error("Cart cleanup retry failed for tenant {}", tenantId, exception);
            } finally {
                restoreTenantContext(previousTenantId);
            }
            nextAfterTenantId = tenantId;
        }
        return nextAfterTenantId;
    }

    private void retryTenant(LocalDateTime cutoff) {
        for (Long checkoutId : intentStore.findReadyCheckoutIds(cutoff)) {
            try {
                processor.process(checkoutId);
            } catch (RuntimeException exception) {
                LOGGER.warn("Cart cleanup retry failed for checkout {}", checkoutId, exception);
            }
        }
    }

    private static void restoreTenantContext(Long tenantId) {
        if (tenantId == null) {
            TenantContext.clear();
        } else {
            TenantContext.setTenantId(tenantId);
        }
    }
}
