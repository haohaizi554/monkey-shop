package com.example.monkey.payment.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.monkey.shared.application.tenant.TenantContext;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class JdbcPaymentRecoveryTenantSourceTest {

    private static final LocalDateTime CUTOFF = LocalDateTime.parse("2026-07-14T08:00:00");

    private JdbcTemplate jdbcTemplate;
    private JdbcPaymentRecoveryTenantSource tenantSource;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:payment_recovery_tenants;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("DROP TABLE IF EXISTS payment_ledger");
        jdbcTemplate.execute("DROP TABLE IF EXISTS payment_order");
        jdbcTemplate.execute("""
                CREATE TABLE payment_order (
                    tenant_id BIGINT NOT NULL,
                    operation_state VARCHAR(32) NOT NULL,
                    lease_expires_at TIMESTAMP NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE payment_ledger (
                    tenant_id BIGINT NOT NULL,
                    ledger_type VARCHAR(32) NOT NULL,
                    operation_state VARCHAR(32) NULL,
                    lease_expires_at TIMESTAMP NULL,
                    audit_state VARCHAR(32) NOT NULL
                )
                """);
        tenantSource = new JdbcPaymentRecoveryTenantSource(jdbcTemplate);
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void discoversDistinctTenantsAcrossPaymentsRefundsAndPendingAuditsWithoutTenantFilter() {
        insertPayment(1L, "RESERVED", CUTOFF.minusMinutes(1));
        insertPayment(2L, "RETRYABLE", CUTOFF);
        insertPayment(3L, "RESERVED", CUTOFF.plusMinutes(1));
        insertRefund(1L, "RETRYABLE", CUTOFF.minusMinutes(2), "WAITING");
        insertRefund(2L, "COMPLETED", null, "PENDING");
        insertLedger(4L, "CAPTURE", null, null, "PENDING");
        TenantContext.setTenantId(999L);

        List<Long> tenants = tenantSource.findTenantIdsReadyForRecovery(CUTOFF, 10);

        assertThat(tenants).containsExactly(1L, 2L);
        assertThat(TenantContext.currentTenantId()).contains(999L);
    }

    @Test
    void deduplicatesOrdersTenantsAndAppliesOrderedBatchLimit() {
        insertPayment(3L, "RESERVED", CUTOFF.minusMinutes(1));
        insertPayment(1L, "RESERVED", CUTOFF.minusMinutes(1));
        insertPayment(2L, "RETRYABLE", CUTOFF.minusMinutes(1));
        insertRefund(1L, "RETRYABLE", CUTOFF.minusMinutes(1), "WAITING");
        insertRefund(2L, "COMPLETED", null, "PENDING");

        assertThat(tenantSource.findTenantIdsReadyForRecovery(CUTOFF, 2)).containsExactly(1L, 2L);
    }

    private void insertPayment(long tenantId, String state, LocalDateTime leaseExpiresAt) {
        jdbcTemplate.update(
                "INSERT INTO payment_order (tenant_id, operation_state, lease_expires_at) VALUES (?, ?, ?)",
                tenantId,
                state,
                leaseExpiresAt);
    }

    private void insertRefund(long tenantId, String state, LocalDateTime leaseExpiresAt, String auditState) {
        insertLedger(tenantId, "REFUND", state, leaseExpiresAt, auditState);
    }

    private void insertLedger(
            long tenantId, String ledgerType, String state, LocalDateTime leaseExpiresAt, String auditState) {
        jdbcTemplate.update(
                "INSERT INTO payment_ledger"
                        + " (tenant_id, ledger_type, operation_state, lease_expires_at, audit_state)"
                        + " VALUES (?, ?, ?, ?, ?)",
                tenantId,
                ledgerType,
                state,
                leaseExpiresAt,
                auditState);
    }
}
