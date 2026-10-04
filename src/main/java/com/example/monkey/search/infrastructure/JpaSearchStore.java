package com.example.monkey.search.infrastructure;

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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.search.store", havingValue = "jpa", matchIfMissing = true)
public class JpaSearchStore implements SearchStore {

    private static final TypeReference<Map<String, Object>> ATTRIBUTES_TYPE = new TypeReference<>() {};
    private static final TypeReference<Map<String, String>> FILTERS_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<String>> TAGS_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbcTemplate;
    private final SearchHistoryRepository searchHistoryRepository;
    private final UserSearchProfileRepository userSearchProfileRepository;
    private final PiiCryptoService piiCryptoService;
    private final ObjectMapper objectMapper;

    public JpaSearchStore(
            JdbcTemplate jdbcTemplate,
            SearchProductSpuRepository ignoredProductSpuRepository,
            SearchHistoryRepository searchHistoryRepository,
            UserSearchProfileRepository userSearchProfileRepository,
            PiiCryptoService piiCryptoService,
            ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.searchHistoryRepository = searchHistoryRepository;
        this.userSearchProfileRepository = userSearchProfileRepository;
        this.piiCryptoService = piiCryptoService;
        this.objectMapper = objectMapper;
    }

    @Override
    public SearchPage search(SearchQuery query) {
        long tenantId = TenantContext.currentTenantIdOrDefault();
        SqlQuery contentQuery = productQuery(query, tenantId);
        List<SearchProduct> content = jdbcTemplate.query(
                contentQuery.sql(),
                (rs, rowNum) -> toSearchProduct(rs),
                contentQuery.parameters().toArray());
        SqlQuery countQuery = countQuery(query, tenantId);
        Long total = jdbcTemplate.queryForObject(
                countQuery.sql(), Long.class, countQuery.parameters().toArray());
        return new SearchPage(content, query.page(), query.size(), total == null ? 0L : total);
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

    private SqlQuery productQuery(SearchQuery query, long tenantId) {
        QueryParts parts = queryParts(query, tenantId, true);
        long offset = (long) query.page() * query.size();
        String sql = """
                SELECT p.id AS product_id,
                       p.category_id,
                       p.name,
                       p.title,
                       p.image_url,
                       p.original_price,
                       p.member_price,
                       p.attributes_json,
                       COALESCE(stock.available_stock, 0) AS stock,
                       %s AS score
                FROM product_spu p
                %s
                WHERE %s
                ORDER BY %s
                LIMIT ? OFFSET ?
                """.formatted(parts.scoreExpression(), stockJoin(), parts.whereClause(), orderBy(query.sort()));
        List<Object> parameters = new ArrayList<>(parts.scoreParameters());
        parameters.addAll(stockParameters(tenantId));
        parameters.addAll(parts.whereParameters());
        parameters.add(query.size());
        parameters.add(offset);
        return new SqlQuery(sql, parameters);
    }

    private SqlQuery countQuery(SearchQuery query, long tenantId) {
        QueryParts parts = queryParts(query, tenantId, false);
        String sql = """
                SELECT COUNT(*)
                FROM product_spu p
                %s
                WHERE %s
                """.formatted(stockJoin(), parts.whereClause());
        List<Object> parameters = stockParameters(tenantId);
        parameters.addAll(parts.whereParameters());
        return new SqlQuery(sql, parameters);
    }

    private static List<Object> stockParameters(long tenantId) {
        return new ArrayList<>(List.of(tenantId, tenantId));
    }

    private QueryParts queryParts(SearchQuery query, long tenantId, boolean includeScore) {
        List<Object> scoreParameters = new ArrayList<>();
        String scoreExpression = scoreExpression(query, scoreParameters, includeScore);
        List<Object> whereParameters = new ArrayList<>();
        StringBuilder where = new StringBuilder("p.tenant_id = ? AND p.deleted = false AND p.status = 'LISTED'");
        whereParameters.add(tenantId);
        if (!query.keyword().isBlank()) {
            where.append(" AND (INSTR(LOWER(COALESCE(p.name, '')), LOWER(?)) > 0");
            where.append(" OR INSTR(LOWER(COALESCE(p.title, '')), LOWER(?)) > 0");
            where.append(" OR ").append(attributeKeywordMatch());
            whereParameters.add(query.normalizedKeyword());
            whereParameters.add(query.normalizedKeyword());
            whereParameters.add(query.normalizedKeyword());
        }
        if (query.categoryId() != null) {
            where.append(" AND p.category_id = ?");
            whereParameters.add(query.categoryId());
        }
        query.attributes().forEach((key, value) -> {
            where.append(" AND JSON_EXTRACT(p.attributes_json, CONCAT('$.', ?)) IS NOT NULL");
            where.append(" AND INSTR(LOWER(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(p.attributes_json, CONCAT('$.', ?))), '')), LOWER(?)) > 0");
            whereParameters.add(key);
            whereParameters.add(key);
            whereParameters.add(value);
        });
        if (query.minPrice() != null) {
            where.append(" AND COALESCE(p.member_price, p.original_price) >= ?");
            whereParameters.add(query.minPrice());
        }
        if (query.maxPrice() != null) {
            where.append(" AND COALESCE(p.member_price, p.original_price) <= ?");
            whereParameters.add(query.maxPrice());
        }
        if (query.inStock()) {
            where.append(" AND COALESCE(stock.available_stock, 0) > 0");
        }
        return new QueryParts(scoreExpression, scoreParameters, where.toString(), whereParameters);
    }

    private static String scoreExpression(SearchQuery query, List<Object> parameters, boolean includeScore) {
        if (!includeScore || query.keyword().isBlank()) {
            return query.keyword().isBlank() ? "1" : "0";
        }
        String keyword = query.normalizedKeyword();
        parameters.add(keyword);
        parameters.add(keyword);
        parameters.add(keyword);
        return "(CASE WHEN INSTR(LOWER(COALESCE(p.name, '')), LOWER(?)) > 0 THEN 80 ELSE 0 END"
                + " + CASE WHEN INSTR(LOWER(COALESCE(p.title, '')), LOWER(?)) > 0 THEN 50 ELSE 0 END"
                + " + CASE WHEN "
                + attributeKeywordMatch()
                + " THEN 30 ELSE 0 END)";
    }

    private static String attributeKeywordMatch() {
        return "EXISTS (SELECT 1 FROM JSON_TABLE(COALESCE(p.attributes_json, '{}'), "
                + "'$.*' COLUMNS (attribute_value VARCHAR(2048) PATH '$')) attribute_values "
                + "WHERE INSTR(LOWER(COALESCE(attribute_values.attribute_value, '')), LOWER(?)) > 0)";
    }

    private static String stockJoin() {
        return """
                LEFT JOIN (
                    SELECT sku.spu_id,
                           SUM(stock.available_quantity) AS available_stock
                    FROM product_sku sku
                    INNER JOIN inventory_stock stock
                        ON stock.sku_id = sku.id
                       AND stock.tenant_id = sku.tenant_id
                    INNER JOIN inventory_warehouse warehouse
                        ON warehouse.id = stock.warehouse_id
                       AND warehouse.tenant_id = sku.tenant_id
                       AND warehouse.active = true
                    WHERE sku.tenant_id = ?
                      AND sku.active = true
                    GROUP BY sku.spu_id
                ) stock ON stock.spu_id = p.id
                         AND p.tenant_id = ?
                """;
    }

    private static String orderBy(SearchSort sort) {
        return switch (sort) {
            case PRICE_ASC -> "COALESCE(p.member_price, p.original_price) ASC, p.id DESC";
            case PRICE_DESC -> "COALESCE(p.member_price, p.original_price) DESC, p.id DESC";
            case NEWEST -> "p.id DESC";
            case HOT, RELEVANCE -> "score DESC, p.id DESC";
        };
    }

    private SearchProduct toSearchProduct(ResultSet rs) throws SQLException {
        return new SearchProduct(
                nullableLong(rs, "product_id"),
                nullableLong(rs, "category_id"),
                rs.getString("name"),
                rs.getString("title"),
                rs.getString("image_url"),
                rs.getBigDecimal("original_price"),
                rs.getBigDecimal("member_price"),
                read(rs.getString("attributes_json"), ATTRIBUTES_TYPE, Map.of()),
                rs.getInt("stock"),
                rs.getInt("score"));
    }

    private UserSearchProfile toProfile(UserSearchProfileEntity entity) {
        return new UserSearchProfile(
                entity.getUserId(),
                piiCryptoService.decrypt(entity.getEncryptedInterestProfile()),
                read(entity.getTagVectorJson(), TAGS_TYPE, List.of()),
                entity.getUpdatedAt(),
                entity.getVersion() == null ? 0L : entity.getVersion());
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
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

    private record SqlQuery(String sql, List<Object> parameters) {}

    private record QueryParts(
            String scoreExpression,
            List<Object> scoreParameters,
            String whereClause,
            List<Object> whereParameters) {}
}
