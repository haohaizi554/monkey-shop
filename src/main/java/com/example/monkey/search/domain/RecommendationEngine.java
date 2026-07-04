package com.example.monkey.search.domain;

import com.example.monkey.membership.domain.BrowseHistoryItem;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class RecommendationEngine {

    private RecommendationEngine() {}

    public static List<RecommendationItem> rank(
            List<SearchProduct> candidates,
            List<BrowseHistoryItem> browseHistory,
            List<PurchasedProduct> purchases,
            UserSearchProfile profile,
            int limit) {
        Set<Long> browsedIds =
                browseHistory.stream().map(BrowseHistoryItem::productId).collect(Collectors.toSet());
        Set<Long> purchasedIds =
                purchases.stream().map(PurchasedProduct::productId).collect(Collectors.toSet());
        List<String> tags = profile == null ? List.of() : profile.tags();
        return candidates.stream()
                .map(product -> score(product, browsedIds, purchasedIds, tags))
                .filter(item -> item.score() > 0)
                .sorted(Comparator.comparing(RecommendationItem::score).reversed())
                .limit(Math.max(1, limit))
                .toList();
    }

    private static RecommendationItem score(
            SearchProduct product, Set<Long> browsedIds, Set<Long> purchasedIds, List<String> tags) {
        int score = 0;
        String reason = "profile match";
        if (browsedIds.contains(product.productId())) {
            score += 60;
            reason = "recent browse";
        }
        if (purchasedIds.contains(product.productId())) {
            score += 80;
            reason = "purchase affinity";
        }
        for (String tag : tags) {
            if (matches(product, tag)) {
                score += 20;
                reason = "interest tag: " + tag;
            }
        }
        score += Math.min(20, product.score());
        return new RecommendationItem(
                product.productId(), product.name(), product.title(), product.imageUrl(), reason, score);
    }

    private static boolean matches(SearchProduct product, String tag) {
        if (tag == null || tag.isBlank()) {
            return false;
        }
        String normalized = tag.trim().toLowerCase(Locale.ROOT);
        return contains(product.name(), normalized)
                || contains(product.title(), normalized)
                || attributesContain(product.attributes(), normalized);
    }

    private static boolean attributesContain(Map<String, Object> attributes, String normalized) {
        if (attributes == null || attributes.isEmpty()) {
            return false;
        }
        return attributes.entrySet().stream()
                .anyMatch(entry ->
                        contains(entry.getKey(), normalized) || contains(String.valueOf(entry.getValue()), normalized));
    }

    private static boolean contains(String value, String normalized) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(normalized);
    }
}
