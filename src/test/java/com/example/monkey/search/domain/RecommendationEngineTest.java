package com.example.monkey.search.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.monkey.membership.domain.BrowseHistoryItem;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RecommendationEngineTest {

    @Test
    void ranksBrowsePurchaseAndProfileSignals() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 4, 10, 0);
        List<SearchProduct> candidates = List.of(
                new SearchProduct(1L, 10L, "Smart Phone", "Fast device", "/1.png", BigDecimal.TEN, null, Map.of(), 5),
                new SearchProduct(
                        2L,
                        10L,
                        "Premium Case",
                        "Family bundle",
                        "/2.png",
                        BigDecimal.ONE,
                        null,
                        Map.of("tag", "premium"),
                        3),
                new SearchProduct(3L, 11L, "Plain Item", "No signal", null, BigDecimal.ONE, null, Map.of(), 1));
        UserSearchProfile profile = new UserSearchProfile(7L, "premium family", List.of("premium"), now, 0);

        List<RecommendationItem> ranked = RecommendationEngine.rank(
                candidates,
                List.of(new BrowseHistoryItem(1L, 7L, 1L, "Smart Phone", null, now, now.plusDays(7))),
                List.of(new PurchasedProduct(2L, "Premium Case", now)),
                profile,
                10);

        assertThat(ranked).extracting(RecommendationItem::productId).containsExactly(2L, 1L, 3L);
        assertThat(ranked.get(0).reason()).contains("interest tag").contains("premium");
    }
}
