package com.example.monkey.order.infrastructure;

import com.example.monkey.order.domain.OrderProductPort;
import com.example.monkey.order.domain.OrderStore.ProductRecord;
import com.example.monkey.shared.application.tenant.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcOrderProductPort implements OrderProductPort {

    private final JdbcTemplate jdbcTemplate;

    public JdbcOrderProductPort(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<ProductRecord> findProductById(Long productId) {
        return jdbcTemplate
                .query("""
                        SELECT id, name, image_url, price, description, stock
                        FROM monkey
                        WHERE id = ? AND tenant_id = ? AND deleted = false
                        """, (rs, rowNum) -> toProductRecord(rs), productId, TenantContext.currentTenantIdOrDefault())
                .stream()
                .findFirst();
    }

    @Override
    public boolean deductProductStock(Long productId) {
        return jdbcTemplate.update("""
                        UPDATE monkey
                        SET stock = stock - 1
                        WHERE id = ? AND tenant_id = ? AND stock > 0 AND deleted = false
                        """, productId, TenantContext.currentTenantIdOrDefault()) > 0;
    }

    @Override
    public boolean restoreProductStock(Long productId) {
        return jdbcTemplate.update("""
                        UPDATE monkey
                        SET stock = stock + 1
                        WHERE id = ? AND tenant_id = ? AND deleted = false
                        """, productId, TenantContext.currentTenantIdOrDefault()) > 0;
    }

    private static ProductRecord toProductRecord(ResultSet rs) throws SQLException {
        return new ProductRecord(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("image_url"),
                rs.getBigDecimal("price"),
                rs.getString("description"),
                rs.getInt("stock"));
    }
}
