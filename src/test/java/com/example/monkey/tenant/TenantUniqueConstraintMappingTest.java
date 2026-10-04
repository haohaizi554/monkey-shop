package com.example.monkey.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.monkey.cart.infrastructure.CartCheckoutLineEntity;
import com.example.monkey.inventory.infrastructure.InventoryReservationEntity;
import com.example.monkey.inventory.infrastructure.InventoryStockLedger;
import com.example.monkey.inventory.infrastructure.InventoryWarehouse;
import com.example.monkey.logistics.infrastructure.FreightTemplateEntity;
import com.example.monkey.logistics.infrastructure.LogisticsTrackingEntity;
import com.example.monkey.logistics.infrastructure.LogisticsTrackingEventEntity;
import com.example.monkey.logistics.infrastructure.LogisticsWebhookLogEntity;
import com.example.monkey.marketing.infrastructure.MarketingCouponEntity;
import com.example.monkey.marketing.infrastructure.MarketingGroupBuyIdempotencyBindingEntity;
import com.example.monkey.marketing.infrastructure.MarketingGroupBuyMemberEntity;
import com.example.monkey.marketing.infrastructure.MarketingUserCouponEntity;
import com.example.monkey.payment.infrastructure.PaymentCallbackLogEntity;
import com.example.monkey.payment.infrastructure.PaymentReconciliationReportEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.Test;

class TenantUniqueConstraintMappingTest {

    private static final List<ExpectedConstraint> EXPECTED_CONSTRAINTS = List.of(
            new ExpectedConstraint(
                    InventoryWarehouse.class,
                    "inventory_warehouse",
                    "uk_inventory_warehouse_code",
                    new String[] {"tenant_id", "code"},
                    "code"),
            new ExpectedConstraint(
                    InventoryReservationEntity.class,
                    "inventory_reservation",
                    "uk_inventory_reservation_key",
                    new String[] {"tenant_id", "reservation_key"},
                    "reservationKey"),
            new ExpectedConstraint(
                    InventoryStockLedger.class,
                    "inventory_stock_ledger",
                    "uk_inventory_ledger_idempotency",
                    new String[] {"tenant_id", "idempotency_key"},
                    "idempotencyKey"),
            new ExpectedConstraint(
                    MarketingCouponEntity.class,
                    "marketing_coupon",
                    "uk_marketing_coupon_code",
                    new String[] {"tenant_id", "code"},
                    "code"),
            new ExpectedConstraint(
                    MarketingUserCouponEntity.class,
                    "marketing_user_coupon",
                    "uk_marketing_user_coupon_idempotency",
                    new String[] {"tenant_id", "idempotency_key"},
                    "idempotencyKey"),
            new ExpectedConstraint(
                    MarketingGroupBuyMemberEntity.class,
                    "marketing_group_buy_member",
                    "uk_marketing_group_buy_member_idempotency",
                    new String[] {"tenant_id", "user_id", "idempotency_key"},
                    "userId",
                    "idempotencyKey"),
            new ExpectedConstraint(
                    MarketingGroupBuyIdempotencyBindingEntity.class,
                    "marketing_group_buy_idempotency_binding",
                    "uk_marketing_group_buy_binding_user_key",
                    new String[] {"tenant_id", "user_id", "idempotency_key"},
                    "userId",
                    "idempotencyKey"),
            new ExpectedConstraint(
                    CartCheckoutLineEntity.class,
                    "cart_checkout_line",
                    "uk_cart_checkout_line_reservation",
                    new String[] {"tenant_id", "reservation_key"},
                    "reservationKey"),
            new ExpectedConstraint(
                    PaymentCallbackLogEntity.class,
                    "payment_callback_log",
                    "uk_payment_callback_provider_id",
                    new String[] {"tenant_id", "provider", "callback_id"},
                    "provider",
                    "callbackId"),
            new ExpectedConstraint(
                    PaymentReconciliationReportEntity.class,
                    "payment_reconciliation_report",
                    "uk_payment_reconciliation_provider_date",
                    new String[] {"tenant_id", "provider", "report_date"},
                    "provider",
                    "reportDate"),
            new ExpectedConstraint(
                    LogisticsTrackingEntity.class,
                    "logistics_tracking",
                    "uk_logistics_tracking_no",
                    new String[] {"tenant_id", "tracking_no"},
                    "trackingNo"),
            new ExpectedConstraint(
                    LogisticsTrackingEventEntity.class,
                    "logistics_tracking_event",
                    "uk_logistics_event_carrier_id",
                    new String[] {"tenant_id", "carrier", "event_id"},
                    "carrier",
                    "eventId"),
            new ExpectedConstraint(
                    LogisticsWebhookLogEntity.class,
                    "logistics_webhook_log",
                    "uk_logistics_webhook_carrier_event",
                    new String[] {"tenant_id", "carrier", "event_id"},
                    "carrier",
                    "eventId"),
            new ExpectedConstraint(
                    FreightTemplateEntity.class,
                    "logistics_freight_template",
                    "uk_logistics_freight_template",
                    new String[] {"tenant_id", "carrier", "province", "charge_mode"},
                    "carrier",
                    "province",
                    "chargeMode"));

    @Test
    void v44TenantUniqueConstraintsAreDeclaredWithPhysicalColumns() {
        EXPECTED_CONSTRAINTS.forEach(expected -> {
            Table table = expected.entityType().getAnnotation(Table.class);

            assertThat(table)
                    .as(
                            "%s should declare a table mapping",
                            expected.entityType().getSimpleName())
                    .isNotNull();
            assertThat(table.name()).isEqualTo(expected.tableName());
            assertThat(table.uniqueConstraints())
                    .as("%s should declare %s", expected.tableName(), expected.constraintName())
                    .anySatisfy(unique -> assertConstraint(unique, expected));
        });
    }

    @Test
    void v44AffectedColumnsDoNotRetainGlobalUniqueFlags() {
        EXPECTED_CONSTRAINTS.forEach(expected -> {
            for (String fieldName : expected.fieldNames()) {
                Field field = findField(expected.entityType(), fieldName);
                Column column = field.getAnnotation(Column.class);

                assertThat(column)
                        .as(
                                "%s.%s should have explicit column metadata",
                                expected.entityType().getSimpleName(), fieldName)
                        .isNotNull();
                assertThat(column.unique())
                        .as("%s.%s must not generate a global unique index", expected.tableName(), fieldName)
                        .isFalse();
            }
        });
    }

    private static void assertConstraint(UniqueConstraint actual, ExpectedConstraint expected) {
        assertThat(actual.name()).isEqualTo(expected.constraintName());
        assertThat(actual.columnNames()).containsExactly(expected.columnNames());
    }

    private static Field findField(Class<?> entityType, String fieldName) {
        try {
            return entityType.getDeclaredField(fieldName);
        } catch (NoSuchFieldException exception) {
            throw new AssertionError("Missing field " + entityType.getName() + "." + fieldName, exception);
        }
    }

    private record ExpectedConstraint(
            Class<?> entityType, String tableName, String constraintName, String[] columnNames, String... fieldNames) {}
}
