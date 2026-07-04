package com.example.monkey.membership.domain;

import java.time.Duration;
import java.util.List;

public interface MembershipActivityStore {

    BrowseHistoryItem record(BrowseHistoryItem item, Duration ttl);

    List<BrowseHistoryItem> findRecent(Long userId, int limit);
}
