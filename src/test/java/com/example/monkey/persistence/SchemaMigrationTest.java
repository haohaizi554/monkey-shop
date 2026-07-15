package com.example.monkey.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SchemaMigrationTest {

    @Test
    void v52PersistsCheckoutFingerprintAndRetryableCartCleanupIntent() throws IOException {
        Path v52Path = Path.of("src/main/resources/db/migration/V52__durable_cart_checkout_cleanup.sql");
        assertThat(v52Path).exists();
        String v52 = Files.readString(v52Path, StandardCharsets.UTF_8);

        assertThat(v52)
                .contains("ADD COLUMN request_fingerprint CHAR(64)")
                .contains("LEGACY_V51_CHECKOUT_REPLAY_SENTINEL_____________________________")
                .doesNotContain("CONCAT('legacy:'")
                .contains("CREATE TABLE cart_cleanup_intent")
                .contains("item_snapshots_json LONGTEXT NOT NULL")
                .contains("JSON_VALID(item_snapshots_json)")
                .contains("JSON_TYPE(item_snapshots_json) = 'ARRAY'")
                .contains("status VARCHAR(32) NOT NULL DEFAULT 'PENDING'")
                .contains("claim_token VARCHAR(64)")
                .contains("lease_expires_at DATETIME(6)")
                .contains("attempt_count INT NOT NULL DEFAULT 0")
                .contains("next_attempt_at DATETIME(6) NOT NULL")
                .contains("last_error VARCHAR(255)")
                .contains("status IN ('PENDING', 'PROCESSING', 'COMPLETED')")
                .contains("status = 'PROCESSING' AND claim_token IS NOT NULL")
                .contains("UNIQUE KEY uk_cart_cleanup_intent_claim (tenant_id, claim_token)")
                .contains("idx_cart_cleanup_intent_pending_ready")
                .contains("idx_cart_cleanup_intent_processing_lease")
                .contains("idx_cart_cleanup_intent_completed_purge (status, completed_at")
                .contains("FOREIGN KEY (tenant_id, checkout_id) REFERENCES cart_checkout (tenant_id, id)");
    }

    @Test
    void v51GuardsLegacyIdempotencyAndDuplicateActivePaymentIntents() throws IOException {
        String v51 = read("src/main/resources/db/migration/V51__payment_request_fingerprints.sql");

        assertThat(v51)
                .doesNotContain("CREATE PROCEDURE", "DROP PROCEDURE", "SIGNAL SQLSTATE")
                .contains("CREATE TEMPORARY TABLE v51_active_payment_preflight")
                .contains("ck_v51_resolve_duplicate_active_payment_intents")
                .contains("operation_state VARCHAR(32) NOT NULL DEFAULT 'LEGACY_UNREPLAYABLE'")
                .contains("attempt_count INT NOT NULL DEFAULT 0")
                .contains("lease_expires_at DATETIME(6)")
                .contains("last_failure_classification VARCHAR(32) NOT NULL DEFAULT 'LEGACY_UNKNOWN'")
                .contains("terminal_failure_code VARCHAR(64)")
                .contains("merchant_token VARCHAR(128)")
                .contains("response_paid_amount DECIMAL(10, 2)")
                .contains("response_refunded_amount DECIMAL(10, 2)")
                .contains("response_payment_status VARCHAR(32)")
                .contains("response_ledger_status VARCHAR(32)")
                .contains("audit_state VARCHAR(32) NOT NULL DEFAULT 'NONE'")
                .contains("audit_event_type VARCHAR(64)")
                .contains("audit_actor_user_id BIGINT")
                .contains("audit_actor_role VARCHAR(32)")
                .contains("audit_source_ip VARCHAR(64)")
                .contains("audit_include_owner BOOLEAN NOT NULL DEFAULT FALSE")
                .contains("audit_detail VARCHAR(255)")
                .contains("operation_state IN ('RESERVED', 'RETRYABLE')")
                .contains("status <> 'FAILED' OR operation_state NOT IN ('RESERVED', 'RETRYABLE')")
                .contains("last_failure_classification = 'LEGACY_UNKNOWN'")
                .contains("terminal_failure_code IS NOT NULL")
                .contains("terminal_failure_code IN ('PROVIDER_REJECTED', 'CARD_DECLINED', 'REFUND_DECLINED')")
                .contains("audit_state IN ('PENDING', 'DELIVERED')")
                .contains("CHECK (ledger_type <> 'REFUND' OR operation_state IS NOT NULL)")
                .doesNotContain("'\"reason\":\"\"'");
    }

    @Test
    void v51BackfillsDedicatedQueryFencingOnlyForLegacyPendingPayments() throws IOException {
        String v51 = read("src/main/resources/db/migration/V51__payment_request_fingerprints.sql");

        assertThat(v51)
                .contains("query_attempt_count INT NOT NULL DEFAULT 0")
                .contains("query_lease_expires_at DATETIME(6)")
                .contains("next_query_at DATETIME(6)")
                .contains("next_query_at = CASE")
                .contains("WHEN status = 'PENDING' THEN create_time")
                .contains("ELSE NULL")
                .contains("idx_payment_order_query_ready")
                .contains("operation_state IN ('COMPLETED', 'LEGACY_UNREPLAYABLE')");
    }

    @Test
    void v50PaymentFixtureContainsDuplicateActiveIntentsAndUnverifiableLegacyRefund() throws IOException {
        String fixture = read("src/test/resources/db/fixtures/payment_v50_task4_review.sql");

        assertThat(fixture)
                .contains("'TASK4-DUPLICATE-ORDER'")
                .contains("'TASK4-DUPLICATE-PAYMENT-1'")
                .contains("'TASK4-DUPLICATE-PAYMENT-2'")
                .contains("'TASK4-LEGACY-REFUND-KEY'")
                .contains("ledger_type, amount, status, request_key");
    }

    @Test
    void flywayIsBoundToValidatedJpaSchema() throws IOException {
        String pom = read("pom.xml");
        String application = read("src/main/resources/application.yml");

        assertThat(pom).contains("<artifactId>flyway-core</artifactId>");
        assertThat(pom).contains("<artifactId>flyway-mysql</artifactId>");
        assertThat(application).contains("flyway:");
        assertThat(application).contains("locations: classpath:db/migration");
        assertThat(application).contains("ddl-auto: validate");
        assertThat(application).contains("hikari:");
        assertThat(application).contains("maximum-pool-size: ${DB_HIKARI_MAXIMUM_POOL_SIZE:20}");
        assertThat(application).contains("leak-detection-threshold: ${DB_HIKARI_LEAK_DETECTION_THRESHOLD:60000}");
    }

    @Test
    void migrationsEndWithDecimalMoneyAndProductSnapshotColumns() throws IOException {
        String v1 = read("src/main/resources/db/migration/V1__init_schema.sql");
        String v2 = read("src/main/resources/db/migration/V2__add_lookup_indexes.sql");
        String v3 = read("src/main/resources/db/migration/V3__order_price_and_product_snapshot.sql");
        String v4 = read("src/main/resources/db/migration/V4__password_history.sql");
        String v5 = read("src/main/resources/db/migration/V5__unified_user_roles.sql");
        String v6 = read("src/main/resources/db/migration/V6__rbac_roles_permissions.sql");
        String v7 = read("src/main/resources/db/migration/V7__admin_totp_mfa.sql");
        String v8 = read("src/main/resources/db/migration/V8__audit_log.sql");
        String v9 = read("src/main/resources/db/migration/V9__user_email_reset_channel.sql");
        String v10 = read("src/main/resources/db/migration/V10__force_password_change.sql");
        String v11 = read("src/main/resources/db/migration/V11__optimistic_lock_versions.sql");
        String v12 = read("src/main/resources/db/migration/V12__stock_log.sql");
        String v13 = read("src/main/resources/db/migration/V13__idempotency_record.sql");
        String v14 = read("src/main/resources/db/migration/V14__soft_delete_and_order_visibility.sql");
        String v15 = read("src/main/resources/db/migration/V15__audit_trace_retention.sql");
        String v16 = read("src/main/resources/db/migration/V16__pii_encryption_columns.sql");
        String v17 = read("src/main/resources/db/migration/V17__order_pii_anonymized_flag.sql");
        String v18 = read("src/main/resources/db/migration/V18__user_email_pii_encryption.sql");
        String v30 = read("src/main/resources/db/migration/V30__payment_order.sql");
        String v31 = read("src/main/resources/db/migration/V31__payment_reconciliation.sql");
        String v32 = read("src/main/resources/db/migration/V32__logistics_tracking.sql");
        String v33 = read("src/main/resources/db/migration/V33__logistics_freight_template.sql");
        String v34 = read("src/main/resources/db/migration/V34__membership_level.sql");
        String v35 = read("src/main/resources/db/migration/V35__membership_points_wallet.sql");
        String v36 = read("src/main/resources/db/migration/V36__membership_collection.sql");
        String v37 = read("src/main/resources/db/migration/V37__search_history.sql");
        String v38 = read("src/main/resources/db/migration/V38__user_search_profile.sql");
        String v39 = read("src/main/resources/db/migration/V39__risk_device_fingerprint.sql");
        String v40 = read("src/main/resources/db/migration/V40__risk_score.sql");
        String v41 = read("src/main/resources/db/migration/V41__risk_audit_queue.sql");
        String v42 = read("src/main/resources/db/migration/V42__tracking_event.sql");
        String v43 = read("src/main/resources/db/migration/V43__user_profile_tag.sql");
        String v44 = read("src/main/resources/db/migration/V44__tenant_isolation.sql");
        String v45 = read("src/main/resources/db/migration/V45__tenant_management.sql");
        String v46 = read("src/main/resources/db/migration/V46__tenant_billing.sql");
        String v47 = read("src/main/resources/db/migration/V47__encrypt_order_review_content.sql");
        String v49 = read("src/main/resources/db/migration/V49__link_checkout_to_orders.sql");
        String v50 = read("src/main/resources/db/migration/V50__bind_coupon_redemption_to_checkout.sql");

        assertThat(v1).contains("CREATE TABLE IF NOT EXISTS `orders`");
        assertThat(v1).contains("`price` DOUBLE");
        assertThat(v2).contains("idx_orders_user_id_create_time");
        assertThat(v3).contains("ADD COLUMN `product_id` BIGINT");
        assertThat(v3).contains("MODIFY COLUMN `price` DECIMAL(10, 2)");
        assertThat(v3).contains("uk_orders_order_no");
        assertThat(v4)
                .contains("ADD COLUMN `password_last_changed_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)");
        assertThat(v4).contains("CREATE TABLE IF NOT EXISTS `password_history`");
        assertThat(v4).contains("idx_password_history_user_created_at");
        assertThat(v5).contains("ADD COLUMN `role` VARCHAR(32) NOT NULL DEFAULT 'USER'");
        assertThat(v5).contains("ADD COLUMN `nickname` VARCHAR(255)");
        assertThat(v5).contains("idx_user_role");
        assertThat(v5).contains("FROM `admin` a");
        assertThat(v5).contains("'ADMIN'");
        assertThat(v6).contains("CREATE TABLE IF NOT EXISTS `roles`");
        assertThat(v6).contains("CREATE TABLE IF NOT EXISTS `permissions`");
        assertThat(v6).contains("CREATE TABLE IF NOT EXISTS `role_permissions`");
        assertThat(v6).contains("CREATE TABLE IF NOT EXISTS `user_roles`");
        assertThat(v6).contains("'ORDER_CREATE'");
        assertThat(v6).contains("JOIN `roles` r ON r.`name` = CASE WHEN u.`role` = 'ADMIN'");
        assertThat(v7).contains("ADD COLUMN `totp_secret` VARCHAR(128)");
        assertThat(v7).contains("ADD COLUMN `mfa_enabled` TINYINT(1) NOT NULL DEFAULT 0");
        assertThat(v7).contains("idx_user_mfa_enabled");
        assertThat(v8).contains("CREATE TABLE IF NOT EXISTS `audit_log`");
        assertThat(v8).contains("`subject_hash` CHAR(64)");
        assertThat(v8).contains("idx_audit_log_event_created_at");
        assertThat(v8).contains("idx_audit_log_subject_created_at");
        assertThat(v9).contains("ADD COLUMN `email` VARCHAR(255)");
        assertThat(v9).contains("idx_user_email");
        assertThat(v10).contains("ADD COLUMN `password_change_required` TINYINT(1) NOT NULL DEFAULT 0");
        assertThat(v10).contains("idx_user_password_change_required");
        assertThat(v11).contains("ALTER TABLE `monkey`");
        assertThat(v11).contains("ALTER TABLE `orders`");
        assertThat(v11).contains("ADD COLUMN `version` BIGINT NOT NULL DEFAULT 0");
        assertThat(v12).contains("CREATE TABLE IF NOT EXISTS `stock_log`");
        assertThat(v12).contains("UNIQUE KEY `uk_stock_log_order_direction` (`order_id`, `direction`)");
        assertThat(v12).contains("KEY `idx_stock_log_product_created_at` (`product_id`, `created_at`)");
        assertThat(v13).contains("CREATE TABLE IF NOT EXISTS `idempotency_record`");
        assertThat(v13).contains("`idempotency_key` VARCHAR(128) NOT NULL");
        assertThat(v13).contains("`request_hash` CHAR(64) NOT NULL");
        assertThat(v13).contains("UNIQUE KEY `uk_idempotency_user_key` (`user_id`, `idempotency_key`)");
        assertThat(v13).contains("KEY `idx_idempotency_expires_at` (`expires_at`)");
        assertThat(v14).contains("ADD COLUMN `deleted` TINYINT(1) NOT NULL DEFAULT 0");
        assertThat(v14).contains("ADD COLUMN `user_hidden` TINYINT(1) NOT NULL DEFAULT 0");
        assertThat(v14).contains("idx_orders_user_hidden_create_time");
        assertThat(v14).contains("idx_monkey_deleted");
        assertThat(v14).contains("idx_address_user_deleted");
        assertThat(v15).contains("ADD COLUMN `trace_id` VARCHAR(128)");
        assertThat(v15).contains("idx_audit_log_trace_id");
        assertThat(v16).contains("MODIFY COLUMN `phone` VARCHAR(1024)");
        assertThat(v16).contains("ADD COLUMN `phone_hmac` CHAR(64)");
        assertThat(v16).contains("ADD COLUMN `receiver_phone_hmac` CHAR(64)");
        assertThat(v16).contains("idx_user_phone_hmac");
        assertThat(v16).contains("idx_orders_receiver_phone_hmac");
        assertThat(v17).contains("ADD COLUMN `pii_anonymized` BOOLEAN NOT NULL DEFAULT FALSE");
        assertThat(v17).contains("idx_orders_retention_pii_batch");
        assertThat(v18).contains("DROP INDEX `idx_user_email`");
        assertThat(v18).contains("MODIFY COLUMN `email` VARCHAR(1024)");
        assertThat(v30).contains("CREATE TABLE payment_order");
        assertThat(v30).contains("CREATE TABLE payment_ledger");
        assertThat(v30).contains("CREATE TABLE payment_callback_log");
        assertThat(v30).contains("bank_card_hmac");
        assertThat(v30).contains("version BIGINT NOT NULL DEFAULT 0");
        assertThat(v31).contains("CREATE TABLE payment_reconciliation_report");
        assertThat(v31).contains("encrypted_report_payload");
        assertThat(v31).contains("uk_payment_reconciliation_provider_date");
        assertThat(v32).contains("CREATE TABLE logistics_tracking");
        assertThat(v32).contains("CREATE TABLE logistics_tracking_event");
        assertThat(v32).contains("CREATE TABLE logistics_webhook_log");
        assertThat(v32).contains("recipient_phone_hmac");
        assertThat(v32).contains("version BIGINT NOT NULL DEFAULT 0");
        assertThat(v33).contains("CREATE TABLE logistics_freight_template");
        assertThat(v33).contains("uk_logistics_freight_template");
        assertThat(v33).contains("'SF'");
        assertThat(v33).contains("'ZTO'");
        assertThat(v33).contains("'YTO'");
        assertThat(v34).contains("CREATE TABLE membership_profile");
        assertThat(v34).contains("real_name_hmac");
        assertThat(v34).contains("id_card_hmac");
        assertThat(v34).contains("MEMBERSHIP_READ");
        assertThat(v34).contains("MEMBERSHIP_WRITE");
        assertThat(v35).contains("CREATE TABLE membership_points_wallet");
        assertThat(v35).contains("CREATE TABLE membership_points_ledger");
        assertThat(v35).contains("uk_membership_check_in_user_date");
        assertThat(v36).contains("CREATE TABLE membership_collection");
        assertThat(v36).contains("CREATE TABLE membership_price_drop_event");
        assertThat(v36).contains("CREATE TABLE membership_browse_history");
        assertThat(v37).contains("CREATE TABLE search_history");
        assertThat(v37).contains("idx_search_history_keyword_created");
        assertThat(v37).contains("clicked_product_id");
        assertThat(v38).contains("CREATE TABLE user_search_profile");
        assertThat(v38).contains("encrypted_interest_profile");
        assertThat(v38).contains("interest_profile_hmac");
        assertThat(v38).contains("SEARCH_READ");
        assertThat(v38).contains("SEARCH_WRITE");
        assertThat(v39).contains("CREATE TABLE risk_device_fingerprint");
        assertThat(v39).contains("device_fingerprint_hash");
        assertThat(v39).contains("phone_hmac");
        assertThat(v39).contains("idx_risk_device_users");
        assertThat(v40).contains("CREATE TABLE risk_score");
        assertThat(v40).contains("signals_json");
        assertThat(v40).contains("idx_risk_score_decision_assessed");
        assertThat(v41).contains("CREATE TABLE risk_audit_queue");
        assertThat(v41).contains("RISK_WRITE");
        assertThat(v41).contains("RISK_REVIEW");
        assertThat(v42).contains("CREATE TABLE tracking_event");
        assertThat(v42).contains("attributes_json JSON");
        assertThat(v42).contains("TRACKING_READ");
        assertThat(v42).contains("TRACKING_ADMIN");
        assertThat(v43).contains("CREATE TABLE user_profile_tag");
        assertThat(v43).contains("encrypted_profile_summary");
        assertThat(v43).contains("profile_summary_hmac");
        assertThat(v43).contains("CREATE TABLE product_profile");
        assertThat(v43).contains("tag_vector_json JSON");
        assertThat(v44).contains("CREATE TABLE tenant");
        assertThat(v44).contains("ALTER TABLE `orders` ADD COLUMN tenant_id");
        assertThat(v44).contains("ALTER TABLE tracking_event ADD COLUMN tenant_id");
        assertThat(v44).contains("fk_orders_tenant");
        assertThat(v44).contains("idx_tracking_event_tenant_type_time");
        assertThat(v45).contains("CREATE TABLE tenant_config");
        assertThat(v45).contains("CREATE TABLE tenant_config_history");
        assertThat(v45).contains("CREATE TABLE tenant_rollout_policy");
        assertThat(v45).contains("TENANT_READ");
        assertThat(v45).contains("TENANT_ADMIN");
        assertThat(v46).contains("CREATE TABLE tenant_billing_account");
        assertThat(v46).contains("CREATE TABLE tenant_bill");
        assertThat(v46).contains("CREATE TABLE tenant_data_export_job");
        assertThat(v46).contains("tenant_billing_reconciliation");
        assertThat(v47).contains("ALTER TABLE order_review");
        assertThat(v47).contains("MODIFY content VARCHAR(2048)");
        assertThat(v49).contains("ADD COLUMN checkout_id BIGINT");
        assertThat(v49).contains("ADD COLUMN formal_order_id BIGINT");
        assertThat(v49).contains("CREATE TABLE order_line");
        assertThat(v49).contains("uk_orders_checkout_sub_order");
        assertThat(v49).contains("uk_order_line_checkout_line");
        assertThat(v50).contains("ADD COLUMN checkout_id BIGINT");
        assertThat(v50).contains("UNIQUE (tenant_id, user_id, coupon_id)");
        assertThat(v50).contains("fk_marketing_user_coupon_checkout_tenant");
        assertThat(v50).contains("UPDATE marketing_user_coupon");
        assertThat(v50).contains("status = 'CLAIMED'");
    }

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }
}
