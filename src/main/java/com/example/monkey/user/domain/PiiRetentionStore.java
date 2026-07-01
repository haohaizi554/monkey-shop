package com.example.monkey.user.domain;

import java.time.LocalDateTime;
import java.util.List;

public interface PiiRetentionStore {

    boolean anonymizeUserProfile(Long userId);

    int anonymizeAddressesForUser(Long userId, int batchSize);

    int anonymizeOrdersForUser(Long userId, String anonymizedBuyer, int batchSize);

    int anonymizeOrdersCreatedBefore(
            List<String> statuses, LocalDateTime cutoff, String anonymizedBuyer, int batchSize);
}
