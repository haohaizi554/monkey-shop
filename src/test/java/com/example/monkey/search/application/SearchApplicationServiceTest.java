package com.example.monkey.search.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.example.monkey.membership.domain.BrowseHistoryItem;
import com.example.monkey.membership.domain.MembershipActivityStore;
import com.example.monkey.order.application.observability.BusinessMetricsService;
import com.example.monkey.search.application.dto.SearchConversionRequestDto;
import com.example.monkey.search.application.dto.SearchProductQueryRequestDto;
import com.example.monkey.search.application.dto.UserSearchProfileRequestDto;
import com.example.monkey.search.domain.HotKeyword;
import com.example.monkey.search.domain.PurchasedProduct;
import com.example.monkey.search.domain.SearchActivityStore;
import com.example.monkey.search.domain.SearchHistoryEntry;
import com.example.monkey.search.domain.SearchPage;
import com.example.monkey.search.domain.SearchProduct;
import com.example.monkey.search.domain.SearchQuery;
import com.example.monkey.search.domain.SearchSort;
import com.example.monkey.search.domain.SearchStore;
import com.example.monkey.search.domain.SearchSuggestion;
import com.example.monkey.search.domain.UserSearchProfile;
import com.example.monkey.shared.application.observability.AuditService;
import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.domain.id.IdGenerator;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SearchApplicationServiceTest {

    private static final SessionUser USER = new SessionUser(7L, "USER");
    private final FakeSearchStore searchStore = new FakeSearchStore();
    private final FakeSearchActivityStore activityStore = new FakeSearchActivityStore();
    private final FakeMembershipActivityStore membershipActivityStore = new FakeMembershipActivityStore();
    private final FakeIdGenerator idGenerator = new FakeIdGenerator();
    private final BusinessMetricsService metricsService = mock(BusinessMetricsService.class);
    private final SearchApplicationService service = new SearchApplicationService(
            searchStore, activityStore, membershipActivityStore, idGenerator, mock(AuditService.class), metricsService);

    @Test
    void searchRecordsHistoryHotKeywordAndSuggestions() {
        var page = service.search(
                new SearchProductQueryRequestDto("phone", null, null, null, SearchSort.RELEVANCE, 0, 10), USER);

        assertThat(page.content()).hasSize(1);
        assertThat(searchStore.history).hasSize(1);
        assertThat(searchStore.history.get(0).normalizedKeyword()).isEqualTo("phone");
        assertThat(activityStore.hot.get("phone")).isEqualTo(1L);
        assertThat(activityStore.suggestions("phone", 10))
                .extracting(SearchSuggestion::keyword)
                .contains("Phone");
    }

    @Test
    void recommendationsUseBrowsePurchaseAndProfileSignals() {
        searchStore.profile = Optional.of(
                new UserSearchProfile(7L, "premium family phone", List.of("premium", "phone"), LocalDateTime.now(), 1));
        searchStore.purchases = List.of(new PurchasedProduct(2L, "Premium Case", LocalDateTime.now()));

        var recommendations = service.recommendations(USER);

        assertThat(recommendations).extracting("productId").contains(1L, 2L);
    }

    @Test
    void profileAndConversionWriteAuditReadyData() {
        var profile = service.upsertProfile(
                USER, new UserSearchProfileRequestDto("premium family shopping", List.of("premium", "family")));
        service.recordConversion(USER, new SearchConversionRequestDto("phone", 1L, "search-result"));

        assertThat(profile.maskedInterestProfile()).startsWith("p***");
        assertThat(searchStore.savedProfile.tags()).containsExactly("premium", "family");
        assertThat(searchStore.history).anyMatch(SearchHistoryEntry::converted);
        verify(metricsService).recordSearchConversion();
    }

    private static final class FakeIdGenerator implements IdGenerator {
        private long next = 9000;

        @Override
        public long nextId() {
            return next++;
        }
    }

    private static final class FakeSearchStore implements SearchStore {
        private final List<SearchProduct> products = List.of(
                new SearchProduct(
                        1L,
                        10L,
                        "Phone",
                        "Smart phone",
                        "/phone.png",
                        BigDecimal.valueOf(999),
                        BigDecimal.valueOf(899),
                        Map.of("tag", "phone"),
                        90),
                new SearchProduct(
                        2L,
                        10L,
                        "Premium Case",
                        "Family premium",
                        "/case.png",
                        BigDecimal.valueOf(99),
                        null,
                        Map.of("tag", "premium"),
                        70));
        private final List<SearchHistoryEntry> history = new ArrayList<>();
        private Optional<UserSearchProfile> profile = Optional.empty();
        private UserSearchProfile savedProfile;
        private List<PurchasedProduct> purchases = List.of();

        @Override
        public SearchPage search(SearchQuery query) {
            List<SearchProduct> matched = products.stream()
                    .filter(product -> query.keyword().isBlank()
                            || product.name().toLowerCase().contains(query.keyword()))
                    .sorted(Comparator.comparing(SearchProduct::score).reversed())
                    .toList();
            return new SearchPage(matched, query.page(), query.size(), matched.size());
        }

        @Override
        public void saveHistory(SearchHistoryEntry entry) {
            history.add(entry);
        }

        @Override
        public Optional<UserSearchProfile> findProfile(Long userId) {
            return profile;
        }

        @Override
        public UserSearchProfile saveProfile(UserSearchProfile profile) {
            savedProfile = profile;
            this.profile = Optional.of(profile);
            return profile;
        }

        @Override
        public List<String> latestKeywords(Long userId, int limit) {
            return history.stream()
                    .map(SearchHistoryEntry::normalizedKeyword)
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<PurchasedProduct> recentPurchases(Long userId, int limit) {
            return purchases;
        }
    }

    private static final class FakeSearchActivityStore implements SearchActivityStore {
        private final Map<String, Long> hot = new HashMap<>();
        private final Map<String, List<SearchSuggestion>> cachedSuggestions = new HashMap<>();

        @Override
        public void recordKeyword(String keyword) {
            hot.merge(keyword, 1L, Long::sum);
        }

        @Override
        public List<HotKeyword> hotKeywords(int limit) {
            return hot.entrySet().stream()
                    .map(entry -> new HotKeyword(entry.getKey(), entry.getValue()))
                    .toList();
        }

        @Override
        public List<SearchSuggestion> suggestions(String prefix, int limit) {
            return cachedSuggestions.getOrDefault(prefix, List.of());
        }

        @Override
        public void cacheSuggestions(String prefix, List<SearchSuggestion> suggestions, Duration ttl) {
            cachedSuggestions.put(prefix, suggestions);
        }

        @Override
        public void refreshHotKeywordSnapshot() {}
    }

    private static final class FakeMembershipActivityStore implements MembershipActivityStore {
        @Override
        public BrowseHistoryItem record(BrowseHistoryItem item, Duration ttl) {
            return item;
        }

        @Override
        public List<BrowseHistoryItem> findRecent(Long userId, int limit) {
            LocalDateTime now = LocalDateTime.now();
            return List.of(new BrowseHistoryItem(1L, userId, 1L, "Phone", "/phone.png", now, now.plusDays(7)));
        }
    }
}
