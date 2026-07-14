package com.example.monkey.payment.infrastructure;

import com.example.monkey.payment.domain.PaymentRecoveryTenantSource;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPaymentRecoveryTenantSource implements PaymentRecoveryTenantSource {

    private final JdbcTemplate jdbcTemplate;

    public JdbcPaymentRecoveryTenantSource(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<Long> findTenantIdsReadyForRecovery(LocalDateTime cutoff, int limit) {
        return jdbcTemplate.queryForList("""
                SELECT tenant_id
                FROM (
                    SELECT tenant_id
                    FROM payment_order
                    WHERE operation_state IN ('RESERVED', 'RETRYABLE')
                      AND lease_expires_at <= ?
                    UNION
                    SELECT tenant_id
                    FROM payment_ledger
                    WHERE ledger_type = 'REFUND'
                      AND (
                          (operation_state IN ('RESERVED', 'RETRYABLE') AND lease_expires_at <= ?)
                          OR audit_state = 'PENDING'
                      )
                ) recovery_tenants
                ORDER BY tenant_id
                LIMIT ?
                """, Long.class, cutoff, cutoff, limit);
    }
}
