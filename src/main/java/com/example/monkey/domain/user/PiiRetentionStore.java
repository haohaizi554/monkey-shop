package com.example.monkey.domain.user;

import java.time.LocalDateTime;
import java.util.List;

public interface PiiRetentionStore {

    boolean anonymizeUserProfile(Long userId);

    void anonymizeAddressesForUser(Long userId);

    void anonymizeOrdersForUser(Long userId, String anonymizedBuyer);

    int anonymizeOrdersCreatedBefore(List<String> statuses, LocalDateTime cutoff, String anonymizedBuyer);
}
