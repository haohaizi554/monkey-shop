package com.example.monkey.search.infrastructure;

import com.example.monkey.product.domain.ProductStatus;
import com.example.monkey.search.domain.PurchasedProduct;
import com.example.monkey.search.domain.SearchHistoryEntry;
import com.example.monkey.search.domain.SearchPage;
import com.example.monkey.search.domain.SearchProduct;
import com.example.monkey.search.domain.SearchQuery;
import com.example.monkey.search.domain.SearchSort;
import com.example.monkey.search.domain.SearchStore;
import com.example.monkey.search.domain.UserSearchProfile;
import com.example.monkey.shared.application.tenant.TenantContext;
import com.example.monkey.shared.infrastructure.privacy.PiiCryptoService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.search.store", havingValue = "jpa", matchIfMissing = true)
public class JpaSearchStore implements SearchStore {

    private static final int PRODUCT_SCAN_LIMIT = 500;
    private static final TypeReference<Map<String, Object>> ATTRIBUTES_TYPE = new TypeReference<>() {};
    private static final TypeReference<Map<String, String>> FILTERS_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<String>> TAGS_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbcTemplate;
    private final SearchProductSpuRepository productSpuRepository;
    private final SearchHistoryRepository searchHistoryRepository;
    private final UserSearchProfileRepository userSearchProfileRepository;
    private final PiiCryptoService piiCryptoService;
    private final ObjectMapper objectMapper;

    public JpaSearchStore(
            JdbcTemplate jdbcTemplate,
            SearchProductSpuRepository productSpuRepository,
            SearchHistoryRepository searchHistoryRepository,
            UserSearchProfileRepository userSearchProfileRepository,
            PiiCryptoService piiCryptoService,
            ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.productSpuRepository = productSpuRepository;
        this.searchHistoryRepository = searchHistoryRepository;
        this.userSearchProfileRepository = userSearchProfileRepository;
        this.piiCryptoService = piiCryptoService;
        this.objectMapper = objectMapper;
    }

    @Override
    public SearchPage search(SearchQuery query) {
        List<SearchProduct> catalogProducts = catalogProducts(query);
        List<SearchProduct> legacyProducts = legacyProducts(query);
        List<SearchProduct> matched = java.util.stream.Stream.concat(catalogProducts.stream(), legacyProducts.stream())
                .filter(product -> product.score() > 0 || query.keyword().isBlank())
                .filter(product -> matchesCategory(product, query))
                .filter(product -> matchesAttributes(product, query.attributes()))
                .sorted(comparator(query.sort()))
                .toList();
        int from = Math.min(query.page() * query.size(), matched.size());
        int to = Math.min(from + query.size(), matched.size());
        return new SearchPage(matched.subList(from, to), query.page(), query.size(), matched.size());
    }

    @Override
    public void saveHistory(SearchHistoryEntry entry) {
        SearchHistoryEntity entity = new SearchHistoryEntity(entry.id());
        entity.setUserId(entry.userId());
        entity.setKeyword(entry.keyword());
        entity.setNormalizedKeyword(entry.normalizedKeyword());
        entity.setCategoryId(entry.categoryId());
        entity.setFiltersJson(write(entry.filters()));
        entity.setClickedProductId(entry.clickedProductId());
        entity.setConverted(entry.converted());
        entity.setResultCount(entry.resultCount());
        entity.setCreatedAt(entry.createdAt());
        searchHistoryRepository.save(entity);
    }

    @Override
    public Optional<UserSearchProfile> findProfile(Long userId) {
        return userSearchProfileRepository.findById(userId).map(this::toProfile);
    }

    @Override
    public UserSearchProfile saveProfile(UserSearchProfile profile) {
        UserSearchProfileEntity entity = userSearchProfileRepository
                .findById(profile.userId())
                .orElseGet(() -> new UserSearchProfileEntity(profile.userId()));
        entity.setEncryptedInterestProfile(piiCryptoService.encrypt(profile.interestProfile()));
        entity.setInterestProfileHmac(piiCryptoService.blindIndex(profile.interestProfile()));
        entity.setTagVectorJson(write(profile.tags()));
        entity.setUpdatedAt(profile.updatedAt());
        return toProfile(userSearchProfileRepository.save(entity));
    }

    @Override
    public List<String> latestKeywords(Long userId, int limit) {
        return searchHistoryRepository
                .findByUserIdAndKeywordIsNotNullOrderByCreatedAtDesc(userId, PageRequest.of(0, Math.max(1, limit)))
                .stream()
                .map(SearchHistoryEntity::getNormalizedKeyword)
                .filter(keyword -> keyword != null && !keyword.isBlank())
                .distinct()
                .toList();
    }

    @Override
    public List<PurchasedProduct> recentPurchases(Long userId, int limit) {
        return jdbcTemplate.query(
                """
                SELECT product_id, product_name, create_time
                FROM orders
                WHERE tenant_id = ?
                  AND user_id = ?
                  AND user_hidden = false
                  AND deleted = false
                ORDER BY create_time DESC
                LIMIT ?
                """,
                (rs, rowNum) -> new PurchasedProduct(
                        nullableLong(rs, "product_id"),
                        rs.getString("product_name"),
                        rs.getTimestamp("create_time").toLocalDateTime()),
                TenantContext.currentTenantIdOrDefault(),
                userId,
                Math.max(1, limit));
    }

    private List<SearchProduct> catalogProducts(SearchQuery query) {
        return productSpuRepository
                .findByStatusOrderByIdDesc(ProductStatus.LISTED, PageRequest.of(0, PRODUCT_SCAN_LIMIT))
                .stream()
                .map(product -> toCatalogProduct(product, query))
                .toList();
    }

    private List<SearchProduct> legacyProducts(SearchQuery query) {
        return jdbcTemplate.query(
                """
                SELECT id, name, breed, price, description, image_url, stock
                FROM monkey
                WHERE tenant_id = ?
                  AND deleted = false
                ORDER BY id DESC
                LIMIT ?
                """,
                (rs, rowNum) -> toLegacyProduct(rs, query),
                TenantContext.currentTenantIdOrDefault(),
                PRODUCT_SCAN_LIMIT);
    }

    private SearchProduct toCatalogProduct(SearchProductSpuEntity product, SearchQuery query) {
        Map<String, Object> attributes = read(product.getAttributesJson(), ATTRIBUTES_TYPE, Map.of());
        String name = product.getName();
        String title = product.getTitle();
        return new SearchProduct(
                product.getId(),
                product.getCategoryId(),
                name,
                title,
                product.getImageUrl(),
                product.getOriginalPrice(),
                product.getMemberPrice(),
                attributes,
                scoreCatalogProduct(name, title, attributes, query));
    }

    private SearchProduct toLegacyProduct(ResultSet rs, SearchQuery query) throws SQLException {
        String breed = rs.getString("breed");
        Integer stock = nullableInt(rs, "stock");
        Map<String, Object> attributes =
                Map.of("breed", breed == null ? "" : breed, "stock", stock == null ? 0 : stock);
        String name = rs.getString("name");
        String description = rs.getString("description");
        return new SearchProduct(
                rs.getLong("id"),
                null,
                name,
                description,
                rs.getString("image_url"),
                rs.getBigDecimal("price"),
                null,
                attributes,
                scoreLegacyProduct(name, breed, description, attributes, query));
    }

    private int scoreCatalogProduct(String name, String title, Map<String, Object> attributes, SearchQuery query) {
        if (query.keyword().isBlank()) {
            return 1;
        }
        int score = 0;
        String keyword = query.normalizedKeyword();
        if (contains(name, keyword)) {
            score += 80;
        }
        if (contains(title, keyword)) {
            score += 50;
        }
        if (attributes.values().stream().anyMatch(value -> contains(String.valueOf(value), keyword))) {
            score += 30;
        }
        return score;
    }

    private int scoreLegacyProduct(
            String name, String breed, String description, Map<String, Object> attributes, SearchQuery query) {
        if (query.keyword().isBlank()) {
            return 1;
        }
        int score = 0;
        String keyword = query.normalizedKeyword();
        if (contains(name, keyword)) {
            score += 80;
        }
        if (contains(breed, keyword)) {
            score += 50;
        }
        if (contains(description, keyword)) {
            score += 30;
        }
        if (attributes.values().stream().anyMatch(value -> contains(String.valueOf(value), keyword))) {
            score += 20;
        }
        return score;
    }

    private UserSearchProfile toProfile(UserSearchProfileEntity entity) {
        return new UserSearchProfile(
                entity.getUserId(),
                piiCryptoService.decrypt(entity.getEncryptedInterestProfile()),
                read(entity.getTagVectorJson(), TAGS_TYPE, List.of()),
                entity.getUpdatedAt(),
                entity.getVersion() == null ? 0L : entity.getVersion());
    }

    private static boolean matchesCategory(SearchProduct product, SearchQuery query) {
        return query.categoryId() == null || query.categoryId().equals(product.categoryId());
    }

    private static boolean matchesAttributes(SearchProduct product, Map<String, String> filters) {
        if (filters == null || filters.isEmpty()) {
            return true;
        }
        return filters.entrySet().stream().allMatch(entry -> {
            Object value = product.attributes().get(entry.getKey());
            return value != null
                    && contains(String.valueOf(value), entry.getValue().toLowerCase(Locale.ROOT));
        });
    }

    private static Comparator<SearchProduct> comparator(SearchSort sort) {
        return switch (sort) {
            case PRICE_ASC -> Comparator.comparing(JpaSearchStore::effectivePrice);
            case PRICE_DESC ->
                Comparator.comparing(JpaSearchStore::effectivePrice).reversed();
            case NEWEST -> Comparator.comparing(SearchProduct::productId).reversed();
            case HOT, RELEVANCE ->
                Comparator.comparing(SearchProduct::score)
                        .reversed()
                        .thenComparing(SearchProduct::productId, Comparator.reverseOrder());
        };
    }

    private static BigDecimal effectivePrice(SearchProduct product) {
        return product.memberPrice() == null ? product.originalPrice() : product.memberPrice();
    }

    private static boolean contains(String value, String keyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(keyword);
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private String write(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Search JSON cannot be serialized", exception);
        }
    }

    private <T> T read(String json, TypeReference<T> type, T fallback) {
        if (json == null || json.isBlank()) {
            return fallback;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Search JSON cannot be deserialized", exception);
        }
    }
}
