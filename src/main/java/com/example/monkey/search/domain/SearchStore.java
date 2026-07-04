package com.example.monkey.search.domain;

import java.util.List;
import java.util.Optional;

public interface SearchStore {

    SearchPage search(SearchQuery query);

    void saveHistory(SearchHistoryEntry entry);

    Optional<UserSearchProfile> findProfile(Long userId);

    UserSearchProfile saveProfile(UserSearchProfile profile);

    List<String> latestKeywords(Long userId, int limit);

    List<PurchasedProduct> recentPurchases(Long userId, int limit);
}
