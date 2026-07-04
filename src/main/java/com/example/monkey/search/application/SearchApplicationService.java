package com.example.monkey.search.application;

import com.example.monkey.membership.domain.BrowseHistoryItem;
import com.example.monkey.membership.domain.MembershipActivityStore;
import com.example.monkey.order.application.observability.BusinessMetricsService;
import com.example.monkey.search.application.dto.HotKeywordDto;
import com.example.monkey.search.application.dto.RecommendationDto;
import com.example.monkey.search.application.dto.SearchConversionRequestDto;
import com.example.monkey.search.application.dto.SearchPageDto;
import com.example.monkey.search.application.dto.SearchProductQueryDto;
import com.example.monkey.search.application.dto.SearchSuggestionDto;
import com.example.monkey.search.application.dto.UserSearchProfileDto;
import com.example.monkey.search.application.dto.UserSearchProfileRequestDto;
import com.example.monkey.search.domain.PurchasedProduct;
import com.example.monkey.search.domain.RecommendationEngine;
import com.example.monkey.search.domain.SearchActivityStore;
import com.example.monkey.search.domain.SearchHistoryEntry;
import com.example.monkey.search.domain.SearchPage;
import com.example.monkey.search.domain.SearchQuery;
import com.example.monkey.search.domain.SearchStore;
import com.example.monkey.search.domain.SearchSuggestion;
import com.example.monkey.search.domain.UserSearchProfile;
import com.example.monkey.shared.application.observability.AuditService;
import com.example.monkey.shared.application.security.AuthenticatedPrincipals;
import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.domain.id.IdGenerator;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class SearchApplicationService {

    private static final Duration SUGGESTION_TTL = Duration.ofHours(1);
    private static final int HOT_LIMIT = 10;
    private static final int RECOMMEND_LIMIT = 12;

    private final SearchStore searchStore;
    private final SearchActivityStore searchActivityStore;
    private final MembershipActivityStore membershipActivityStore;
    private final IdGenerator idGenerator;
    private final AuditService auditService;
    private final BusinessMetricsService businessMetricsService;

    public SearchApplicationService(
            SearchStore searchStore,
            SearchActivityStore searchActivityStore,
            MembershipActivityStore membershipActivityStore,
            IdGenerator idGenerator,
            AuditService auditService,
            BusinessMetricsService businessMetricsService) {
        this.searchStore = searchStore;
        this.searchActivityStore = searchActivityStore;
        this.membershipActivityStore = membershipActivityStore;
        this.idGenerator = idGenerator;
        this.auditService = auditService;
        this.businessMetricsService = businessMetricsService;
    }

    @WithSpan("search.products")
    @Transactional
    public SearchPageDto search(SearchProductQueryDto request, SessionUser currentUser) {
        SearchQuery query = request == null
                ? new SearchProductQueryDto(null, null, null, null, null, 0, 20).toQuery()
                : request.toQuery();
        SearchPage page = searchStore.search(query);
        if (StringUtils.hasText(query.keyword())) {
            Long userId = currentUser == null ? null : currentUser.id();
            searchActivityStore.recordKeyword(query.keyword());
            searchActivityStore.cacheSuggestions(
                    query.keyword(), suggestionsFrom(page, query.keyword()), SUGGESTION_TTL);
            searchStore.saveHistory(new SearchHistoryEntry(
                    idGenerator.nextId(),
                    userId,
                    query.keyword(),
                    query.normalizedKeyword(),
                    query.categoryId(),
                    query.attributes(),
                    null,
                    false,
                    (int) page.totalElements(),
                    LocalDateTime.now()));
            auditService.record(
                    AuditService.SEARCH_QUERY_RECORDED,
                    AuditService.OUTCOME_SUCCESS,
                    userId,
                    currentUser == null ? null : currentUser.role(),
                    "search:" + query.normalizedKeyword(),
                    null,
                    "resultCount=" + page.totalElements());
        }
        return SearchDtoAssembler.toPage(page);
    }

    @WithSpan("search.suggestions")
    @Transactional(readOnly = true)
    public List<SearchSuggestionDto> suggestions(String prefix, SessionUser currentUser) {
        String normalized = normalize(prefix);
        List<SearchSuggestion> cached = searchActivityStore.suggestions(normalized, HOT_LIMIT);
        if (!cached.isEmpty()) {
            return cached.stream().map(SearchDtoAssembler::toSuggestion).toList();
        }
        Long userId = currentUser == null ? null : currentUser.id();
        Set<String> values = new LinkedHashSet<>();
        if (userId != null) {
            values.addAll(searchStore.latestKeywords(userId, HOT_LIMIT));
        }
        searchActivityStore.hotKeywords(20).stream()
                .map(keyword -> keyword.keyword())
                .filter(keyword -> keyword.startsWith(normalized))
                .forEach(values::add);
        return values.stream()
                .limit(HOT_LIMIT)
                .map(keyword -> new SearchSuggestion(keyword, userId == null ? "hot" : "history", 1))
                .map(SearchDtoAssembler::toSuggestion)
                .toList();
    }

    @WithSpan("search.hot-keywords")
    @Transactional(readOnly = true)
    public List<HotKeywordDto> hotKeywords() {
        return searchActivityStore.hotKeywords(HOT_LIMIT).stream()
                .map(SearchDtoAssembler::toHotKeyword)
                .toList();
    }

    @WithSpan("search.recommendations")
    @Transactional(readOnly = true)
    public List<RecommendationDto> recommendations(SessionUser currentUser) {
        Long userId = AuthenticatedPrincipals.requireUserId(currentUser);
        List<BrowseHistoryItem> browseHistory = membershipActivityStore.findRecent(userId, 20);
        List<PurchasedProduct> purchases = searchStore.recentPurchases(userId, 20);
        UserSearchProfile profile =
                searchStore.findProfile(userId).orElseGet(() -> defaultProfile(userId, browseHistory));
        SearchPage candidates = searchStore.search(new SearchQuery(null, null, null, null, 0, 100));
        return RecommendationEngine.rank(candidates.content(), browseHistory, purchases, profile, RECOMMEND_LIMIT)
                .stream()
                .map(SearchDtoAssembler::toRecommendation)
                .toList();
    }

    @WithSpan("search.profile-upsert")
    @Transactional
    public UserSearchProfileDto upsertProfile(SessionUser currentUser, UserSearchProfileRequestDto request) {
        Long userId = AuthenticatedPrincipals.requireUserId(currentUser);
        UserSearchProfile saved = searchStore.saveProfile(
                new UserSearchProfile(userId, request.interestProfile(), request.tags(), LocalDateTime.now(), 0L));
        auditService.record(
                AuditService.SEARCH_PROFILE_UPDATED,
                AuditService.OUTCOME_SUCCESS,
                userId,
                currentUser.role(),
                "search-profile:" + userId,
                null,
                "tagCount=" + saved.tags().size());
        return SearchDtoAssembler.toProfile(saved);
    }

    @WithSpan("search.conversion")
    @Transactional
    public void recordConversion(SessionUser currentUser, SearchConversionRequestDto request) {
        Long userId = AuthenticatedPrincipals.requireUserId(currentUser);
        String normalizedKeyword = normalize(request.keyword());
        searchStore.saveHistory(new SearchHistoryEntry(
                idGenerator.nextId(),
                userId,
                request.keyword(),
                normalizedKeyword,
                null,
                null,
                request.productId(),
                true,
                1,
                LocalDateTime.now()));
        businessMetricsService.recordSearchConversion();
        auditService.record(
                AuditService.SEARCH_CONVERSION_RECORDED,
                AuditService.OUTCOME_SUCCESS,
                userId,
                currentUser.role(),
                "product:" + request.productId(),
                null,
                "keyword=" + normalizedKeyword + ",source=" + request.source());
    }

    @Scheduled(cron = "${app.search.hot-refresh-cron:0 */5 * * * *}")
    @SchedulerLock(name = "search-hot-keyword-refresh", lockAtMostFor = "${app.search.hot-lock-at-most-for:PT1M}")
    public void refreshHotKeywordSnapshot() {
        searchActivityStore.refreshHotKeywordSnapshot();
    }

    private static List<SearchSuggestion> suggestionsFrom(SearchPage page, String keyword) {
        return page.content().stream()
                .limit(5)
                .map(product -> new SearchSuggestion(product.name(), "product", product.score()))
                .toList();
    }

    private static UserSearchProfile defaultProfile(Long userId, List<BrowseHistoryItem> browseHistory) {
        List<String> tags = browseHistory.stream()
                .map(BrowseHistoryItem::productName)
                .filter(StringUtils::hasText)
                .map(SearchApplicationService::normalize)
                .limit(5)
                .toList();
        return new UserSearchProfile(userId, "recent browse affinity", tags, LocalDateTime.now(), 0L);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
