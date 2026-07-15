package com.example.monkey.cart.infrastructure;

import com.example.monkey.cart.domain.CartCleanupTenantSource;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcCartCleanupTenantSource implements CartCleanupTenantSource {

    private final JdbcTemplate jdbcTemplate;

    public JdbcCartCleanupTenantSource(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<Long> findTenantIdsWithReadyIntents(LocalDateTime cutoff, long afterTenantId, int limit) {
        return jdbcTemplate.queryForList("""
                SELECT DISTINCT tenant_id
                FROM cart_cleanup_intent
                WHERE status = 'PENDING'
                  AND next_attempt_at <= ?
                  AND tenant_id > ?
                ORDER BY tenant_id
                LIMIT ?
                """, Long.class, cutoff, afterTenantId, Math.max(1, limit));
    }
}
