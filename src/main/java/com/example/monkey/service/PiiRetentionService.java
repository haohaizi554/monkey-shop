package com.example.monkey.service;

import com.example.monkey.domain.order.OrderStatus;
import com.example.monkey.domain.user.PiiRetentionStore;
import com.example.monkey.shared.api.ErrorCode;
import com.example.monkey.shared.exception.BusinessException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PiiRetentionService {

    private static final String ANONYMIZED_BUYER = "anonymous";

    private final PiiRetentionStore piiRetentionStore;
    private final Duration orderPiiRetention;
    private final Clock clock;

    public PiiRetentionService(
            PiiRetentionStore piiRetentionStore,
            @Value("${app.pii.retention.order-pii-retention:PT4380H}") Duration orderPiiRetention) {
        this(piiRetentionStore, orderPiiRetention, Clock.systemUTC());
    }

    PiiRetentionService(PiiRetentionStore piiRetentionStore, Duration orderPiiRetention, Clock clock) {
        this.piiRetentionStore = piiRetentionStore;
        this.orderPiiRetention = orderPiiRetention == null ? Duration.ofDays(183) : orderPiiRetention;
        this.clock = clock;
    }

    @Transactional
    public void forgetUser(Long userId) {
        if (!piiRetentionStore.anonymizeUserProfile(userId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "user not found");
        }
        piiRetentionStore.anonymizeAddressesForUser(userId);
        piiRetentionStore.anonymizeOrdersForUser(userId, ANONYMIZED_BUYER);
    }

    @Scheduled(cron = "${app.pii.retention.anonymize-cron:0 30 3 * * *}")
    @SchedulerLock(
            name = "piiRetentionAnonymizeCompletedOrders",
            lockAtMostFor = "${app.pii.retention.lock-at-most-for:PT30M}",
            lockAtLeastFor = "${app.pii.retention.lock-at-least-for:PT1M}")
    @Transactional
    public int anonymizeCompletedOrdersForRetention() {
        LocalDateTime cutoff = LocalDateTime.now(clock).minus(orderPiiRetention);
        List<String> finalStatuses = List.of(OrderStatus.COMPLETED.label(), OrderStatus.REFUNDED.label());
        return piiRetentionStore.anonymizeOrdersCreatedBefore(finalStatuses, cutoff, ANONYMIZED_BUYER);
    }
}
