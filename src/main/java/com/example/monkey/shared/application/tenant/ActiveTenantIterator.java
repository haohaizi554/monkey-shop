package com.example.monkey.shared.application.tenant;

import com.example.monkey.tenant.domain.ActiveTenantReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class ActiveTenantIterator {

    private static final Logger log = LoggerFactory.getLogger(ActiveTenantIterator.class);

    private final ActiveTenantReader activeTenantReader;
    private final TransactionTemplate requiresNew;

    public ActiveTenantIterator(ActiveTenantReader activeTenantReader, PlatformTransactionManager transactionManager) {
        this.activeTenantReader = Objects.requireNonNull(activeTenantReader, "activeTenantReader");
        this.requiresNew = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public IterationResult forEachActiveTenant(TenantWork tenantWork) {
        Objects.requireNonNull(tenantWork, "tenantWork");
        List<Long> successfulTenantIds = new ArrayList<>();
        List<Long> failedTenantIds = new ArrayList<>();
        long affectedRows = 0L;
        Long previousTenantId = TenantContext.currentTenantId().orElse(null);
        try {
            for (Long tenantId : activeTenantReader.findActiveTenantIds()) {
                if (tenantId == null) {
                    continue;
                }
                TenantContext.setTenantId(tenantId);
                try {
                    Long tenantAffectedRows = requiresNew.execute(status -> tenantWork.execute(tenantId));
                    successfulTenantIds.add(tenantId);
                    affectedRows += tenantAffectedRows == null ? 0L : tenantAffectedRows;
                } catch (RuntimeException exception) {
                    failedTenantIds.add(tenantId);
                    log.error("Active tenant job failed for tenantId={}", tenantId, exception);
                } finally {
                    restoreTenantContext(previousTenantId);
                }
            }
        } finally {
            restoreTenantContext(previousTenantId);
        }
        return new IterationResult(successfulTenantIds, failedTenantIds, affectedRows);
    }

    private static void restoreTenantContext(Long tenantId) {
        if (tenantId == null) {
            TenantContext.clear();
        } else {
            TenantContext.setTenantId(tenantId);
        }
    }

    @FunctionalInterface
    public interface TenantWork {

        long execute(Long tenantId);
    }

    public record IterationResult(List<Long> successfulTenantIds, List<Long> failedTenantIds, long affectedRows) {

        public IterationResult {
            successfulTenantIds = List.copyOf(successfulTenantIds);
            failedTenantIds = List.copyOf(failedTenantIds);
        }
    }
}
