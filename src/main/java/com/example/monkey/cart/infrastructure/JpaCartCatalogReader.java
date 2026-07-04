package com.example.monkey.cart.infrastructure;

import com.example.monkey.cart.domain.CartCatalogReader;
import com.example.monkey.cart.domain.CartSkuSnapshot;
import com.example.monkey.shared.application.tenant.TenantContext;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.cart.catalog-reader.provider", havingValue = "jpa", matchIfMissing = true)
public class JpaCartCatalogReader implements CartCatalogReader {

    private static final String LISTED_PRODUCT_STATUS = "LISTED";

    private final JdbcTemplate jdbcTemplate;

    public JpaCartCatalogReader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<CartSkuSnapshot> findActiveSku(Long skuId) {
        return jdbcTemplate
                .query(
                        """
                        SELECT sku.id,
                               sku.spu_id,
                               sku.sku_code,
                               spu.category_id,
                               COALESCE(spu.title, spu.name) AS product_name,
                               spu.image_url,
                               COALESCE(sku.member_price, sku.original_price) AS sale_price
                        FROM product_sku sku
                        JOIN product_spu spu
                          ON spu.id = sku.spu_id
                         AND spu.tenant_id = sku.tenant_id
                        WHERE sku.id = ?
                          AND sku.tenant_id = ?
                          AND sku.active = true
                          AND spu.deleted = false
                          AND spu.status = ?
                          AND COALESCE(sku.member_price, sku.original_price) >= 0
                        """,
                        (rs, rowNum) -> toSnapshot(rs),
                        skuId,
                        TenantContext.currentTenantIdOrDefault(),
                        LISTED_PRODUCT_STATUS)
                .stream()
                .findFirst();
    }

    private static CartSkuSnapshot toSnapshot(ResultSet rs) throws SQLException {
        BigDecimal salePrice = rs.getBigDecimal("sale_price");
        return new CartSkuSnapshot(
                rs.getLong("id"),
                rs.getLong("spu_id"),
                rs.getLong("category_id"),
                rs.getString("sku_code"),
                rs.getString("product_name"),
                rs.getString("image_url"),
                salePrice);
    }
}
