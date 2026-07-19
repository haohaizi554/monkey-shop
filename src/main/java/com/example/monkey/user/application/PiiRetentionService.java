package com.example.monkey.user.application;

import com.example.monkey.order.domain.OrderStatus;
import com.example.monkey.shared.application.tenant.ActiveTenantIterator;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.user.domain.PiiRetentionStore;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PiiRetentionService {

    private static final String ANONYMIZED_BUYER = "anonymous";
    private static final int DEFAULT_RETENTION_BATCH_SIZE = 500;

    private final PiiRetentionStore piiRetentionStore;
    private final ActiveTenantIterator activeTenantIterator;
    private final Duration orderPiiRetention;
    private final int retentionBatchSize;
    private final Clock clock;

    @Autowired
    public PiiRetentionService(
            PiiRetentionStore piiRetentionStore,
            ActiveTenantIterator activeTenantIterator,
            @Value("${app.pii.retention.order-pii-retention:PT4380H}") Duration orderPiiRetention,
            @Value("${app.pii.retention.batch-size:500}") int retentionBatchSize) {
        this(piiRetentionStore, activeTenantIterator, orderPiiRetention, retentionBatchSize, Clock.systemUTC());
    }

    PiiRetentionService(PiiRetentionStore piiRetentionStore, Duration orderPiiRetention, Clock clock) {
        this(piiRetentionStore, null, orderPiiRetention, DEFAULT_RETENTION_BATCH_SIZE, clock);
    }

    PiiRetentionService(
            PiiRetentionStore piiRetentionStore, Duration orderPiiRetention, int retentionBatchSize, Clock clock) {
        this(piiRetentionStore, null, orderPiiRetention, retentionBatchSize, clock);
    }

    PiiRetentionService(
            PiiRetentionStore piiRetentionStore,
            ActiveTenantIterator activeTenantIterator,
            Duration orderPiiRetention,
            int retentionBatchSize,
            Clock clock) {
        this.piiRetentionStore = piiRetentionStore;
        this.activeTenantIterator = activeTenantIterator;
        this.orderPiiRetention = orderPiiRetention == null ? Duration.ofDays(183) : orderPiiRetention;
        this.retentionBatchSize = Math.max(1, retentionBatchSize);
        this.clock = clock;
    }

    @Transactional
    public void forgetUser(Long userId) {
        if (!piiRetentionStore.anonymizeUserProfile(userId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "user not found");
        }
        piiRetentionStore.anonymizeAddressesForUser(userId, retentionBatchSize);
        piiRetentionStore.anonymizeOrdersForUser(userId, ANONYMIZED_BUYER, retentionBatchSize);
    }

    @Scheduled(cron = "${app.pii.retention.anonymize-cron:0 30 3 * * *}")
    @SchedulerLock(
            name = "piiRetentionAnonymizeCompletedOrders",
            lockAtMostFor = "${app.pii.retention.lock-at-most-for:PT30M}",
            lockAtLeastFor = "${app.pii.retention.lock-at-least-for:PT1M}")
    public int anonymizeCompletedOrdersForRetention() {
        LocalDateTime cutoff = LocalDateTime.now(clock).minus(orderPiiRetention);
        List<String> finalStatuses = List.of(OrderStatus.COMPLETED.label(), OrderStatus.REFUNDED.label());
        if (activeTenantIterator == null) {
            return anonymizeCurrentTenant(finalStatuses, cutoff);
        }
        long affectedRows = activeTenantIterator
                .forEachActiveTenant(tenantId -> anonymizeCurrentTenant(finalStatuses, cutoff))
                .affectedRows();
        return Math.toIntExact(affectedRows);
    }

    private int anonymizeCurrentTenant(List<String> finalStatuses, LocalDateTime cutoff) {
        return piiRetentionStore.anonymizeOrdersCreatedBefore(
                finalStatuses, cutoff, ANONYMIZED_BUYER, retentionBatchSize);
    }
}
