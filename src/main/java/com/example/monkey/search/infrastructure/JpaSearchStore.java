package com.example.monkey.search.infrastructure;

import com.example.monkey.order.infrastructure.OrderRepository;
import com.example.monkey.product.domain.ProductStatus;
import com.example.monkey.product.infrastructure.Monkey;
import com.example.monkey.product.infrastructure.MonkeyRepository;
import com.example.monkey.product.infrastructure.ProductSpu;
import com.example.monkey.product.infrastructure.ProductSpuRepository;
import com.example.monkey.search.domain.PurchasedProduct;
import com.example.monkey.search.domain.SearchHistoryEntry;
import com.example.monkey.search.domain.SearchPage;
import com.example.monkey.search.domain.SearchProduct;
import com.example.monkey.search.domain.SearchQuery;
import com.example.monkey.search.domain.SearchSort;
import com.example.monkey.search.domain.SearchStore;
import com.example.monkey.search.domain.UserSearchProfile;
import com.example.monkey.shared.infrastructure.privacy.PiiCryptoService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.search.store", havingValue = "jpa", matchIfMissing = true)
public class JpaSearchStore implements SearchStore {

    private static final int PRODUCT_SCAN_LIMIT = 500;
    private static final TypeReference<Map<String, Object>> ATTRIBUTES_TYPE = new TypeReference<>() {};
    private static final TypeReference<Map<String, String>> FILTERS_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<String>> TAGS_TYPE = new TypeReference<>() {};

    private final ProductSpuRepository productSpuRepository;
    private final MonkeyRepository monkeyRepository;
    private final SearchHistoryRepository searchHistoryRepository;
    private final UserSearchProfileRepository userSearchProfileRepository;
    private final OrderRepository orderRepository;
    private final PiiCryptoService piiCryptoService;
    private final ObjectMapper objectMapper;

    public JpaSearchStore(
            ProductSpuRepository productSpuRepository,
            MonkeyRepository monkeyRepository,
            SearchHistoryRepository searchHistoryRepository,
            UserSearchProfileRepository userSearchProfileRepository,
            OrderRepository orderRepository,
            PiiCryptoService piiCryptoService,
            ObjectMapper objectMapper) {
        this.productSpuRepository = productSpuRepository;
        this.monkeyRepository = monkeyRepository;
        this.searchHistoryRepository = searchHistoryRepository;
        this.userSearchProfileRepository = userSearchProfileRepository;
        this.orderRepository = orderRepository;
        this.piiCryptoService = piiCryptoService;
        this.objectMapper = objectMapper;
    }

    @Override
    public SearchPage search(SearchQuery query) {
        List<SearchProduct> catalogProducts =
                productSpuRepository
                        .findByStatusOrderByIdDesc(ProductStatus.LISTED, PageRequest.of(0, PRODUCT_SCAN_LIMIT))
                        .stream()
                        .map(product -> toProduct(product, query))
                        .toList();
        List<SearchProduct> legacyProducts = monkeyRepository.findAllBy(PageRequest.of(0, PRODUCT_SCAN_LIMIT)).stream()
                .map(product -> toProduct(product, query))
                .toList();
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
        return orderRepository
                .findByUserIdAndUserHiddenFalse(
                        userId, PageRequest.of(0, Math.max(1, limit), Sort.by(Sort.Direction.DESC, "createTime")))
                .stream()
                .map(order -> new PurchasedProduct(order.getProductId(), order.getProductName(), order.getCreateTime()))
                .toList();
    }

    private SearchProduct toProduct(ProductSpu product, SearchQuery query) {
        Map<String, Object> attributes = read(product.getAttributesJson(), ATTRIBUTES_TYPE, Map.of());
        return new SearchProduct(
                product.getId(),
                product.getCategoryId(),
                product.getName(),
                product.getTitle(),
                product.getImageUrl(),
                product.getOriginalPrice(),
                product.getMemberPrice(),
                attributes,
                score(product, attributes, query));
    }

    private SearchProduct toProduct(Monkey product, SearchQuery query) {
        Map<String, Object> attributes = Map.of(
                "breed", product.getBreed() == null ? "" : product.getBreed(),
                "stock", product.getStock() == null ? 0 : product.getStock());
        return new SearchProduct(
                product.getId(),
                null,
                product.getName(),
                product.getDescription(),
                product.getImageUrl(),
                product.getPrice(),
                null,
                attributes,
                score(product, attributes, query));
    }

    private int score(ProductSpu product, Map<String, Object> attributes, SearchQuery query) {
        if (query.keyword().isBlank()) {
            return 1;
        }
        int score = 0;
        String keyword = query.normalizedKeyword();
        if (contains(product.getName(), keyword)) {
            score += 80;
        }
        if (contains(product.getTitle(), keyword)) {
            score += 50;
        }
        if (attributes.values().stream().anyMatch(value -> contains(String.valueOf(value), keyword))) {
            score += 30;
        }
        return score;
    }

    private int score(Monkey product, Map<String, Object> attributes, SearchQuery query) {
        if (query.keyword().isBlank()) {
            return 1;
        }
        int score = 0;
        String keyword = query.normalizedKeyword();
        if (contains(product.getName(), keyword)) {
            score += 80;
        }
        if (contains(product.getBreed(), keyword)) {
            score += 50;
        }
        if (contains(product.getDescription(), keyword)) {
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
