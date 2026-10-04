package com.example.monkey.cart.infrastructure;

import com.example.monkey.cart.domain.CartCatalogReader;
import com.example.monkey.cart.domain.CartSkuSnapshot;
import com.example.monkey.product.domain.PriceContext;
import com.example.monkey.product.domain.ProductPriceBook;
import com.example.monkey.product.domain.ProductPriceStrategy;
import com.example.monkey.shared.application.tenant.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.cart.catalog-reader.provider", havingValue = "jpa", matchIfMissing = true)
public class JpaCartCatalogReader implements CartCatalogReader {

    private static final String LISTED_PRODUCT_STATUS = "LISTED";
    private static final TypeReference<Map<String, BigDecimal>> REGION_PRICE_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ProductPriceStrategy priceStrategy;

    public JpaCartCatalogReader(
            JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, ProductPriceStrategy priceStrategy) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.priceStrategy = priceStrategy;
    }

    @Override
    public Optional<CartSkuSnapshot> findActiveSku(Long skuId, PriceContext priceContext) {
        return jdbcTemplate
                .query(
                        """
                        SELECT sku.id,
                               sku.spu_id,
                               spu.shop_id,
                               sku.sku_code,
                               spu.category_id,
                               COALESCE(spu.title, spu.name) AS product_name,
                               spu.image_url,
                               sku.original_price,
                               sku.member_price,
                               sku.strike_price,
                               sku.region_prices_json
                        FROM product_sku sku
                        JOIN product_spu spu
                          ON spu.id = sku.spu_id
                         AND spu.tenant_id = sku.tenant_id
                        WHERE sku.id = ?
                          AND sku.tenant_id = ?
                          AND sku.active = true
                          AND spu.deleted = false
                          AND spu.status = ?
                          AND spu.shop_id > 0
                          AND sku.original_price > 0
                        """,
                        (rs, rowNum) -> toSnapshot(rs, priceContext),
                        skuId,
                        TenantContext.currentTenantIdOrDefault(),
                        LISTED_PRODUCT_STATUS)
                .stream()
                .findFirst();
    }

    private CartSkuSnapshot toSnapshot(ResultSet rs, PriceContext priceContext) throws SQLException {
        ProductPriceBook priceBook = new ProductPriceBook(
                rs.getBigDecimal("original_price"),
                rs.getBigDecimal("member_price"),
                rs.getBigDecimal("strike_price"),
                readRegionPrices(rs.getString("region_prices_json")));
        BigDecimal salePrice = priceStrategy.quote(priceBook, priceContext).salePrice();
        return new CartSkuSnapshot(
                rs.getLong("id"),
                rs.getLong("spu_id"),
                rs.getLong("shop_id"),
                rs.getLong("category_id"),
                rs.getString("sku_code"),
                rs.getString("product_name"),
                rs.getString("image_url"),
                salePrice);
    }

    private Map<String, BigDecimal> readRegionPrices(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, REGION_PRICE_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Catalog region prices cannot be deserialized", exception);
        }
    }
}
