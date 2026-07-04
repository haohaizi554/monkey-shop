package com.example.monkey.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class Ws5FulfillmentWorkflowTest {

    @Test
    void fulfillmentArtifactsWirePartialShipmentAutoReceiptReviewAndFrontend() throws IOException {
        String docs = read("docs/order/ws5-fulfillment.md");
        String shipmentMigration = read("src/main/resources/db/migration/V28__order_partial_shipment.sql");
        String reviewMigration = read("src/main/resources/db/migration/V29__order_review.sql");
        String service = read("src/main/java/com/example/monkey/order/application/OrderService.java");
        String controller = read("src/main/java/com/example/monkey/order/interfaces/OrderController.java");
        String store = read("src/main/java/com/example/monkey/order/infrastructure/JpaOrderFulfillmentStore.java");
        String stateMachine = read("src/main/java/com/example/monkey/order/domain/OrderTransitionPolicy.java");
        String audit = read("src/main/java/com/example/monkey/shared/application/observability/AuditService.java");
        String frontendApi = read("frontend/src/api/orders.ts");
        String reviewView = read("frontend/src/views/ReviewView.vue");

        assertThat(docs).contains("partial shipment", "automatic receipt", "one review per user/order/SKU");
        assertThat(shipmentMigration)
                .contains("CREATE TABLE order_fulfillment_item", "CREATE TABLE order_shipment_batch");
        assertThat(shipmentMigration).contains("CREATE TABLE order_shipment_line", "uk_order_shipment_tracking");
        assertThat(reviewMigration).contains("CREATE TABLE order_review", "uk_order_review_user_order_sku");
        assertThat(service).contains("@SchedulerLock", "order-auto-receive-shipments");
        assertThat(service).contains("OrderEvent.SHIP_PARTIAL", "OrderEvent.RECEIVE_PARTIAL", "ORDER_REVIEWED");
        assertThat(controller).contains("/shipments/{id}", "/shipments/receive/{id}", "/review/{id}");
        assertThat(store).contains("@ConditionalOnProperty", "app.order.fulfillment-store");
        assertThat(stateMachine).contains("PARTIALLY_SHIPPED", "PARTIALLY_RECEIVED");
        assertThat(audit).contains("ORDER_SHIPMENT_CREATED", "ORDER_PARTIALLY_RECEIVED");
        assertThat(frontendApi).contains("createShipment", "receiveShipment", "reviewOrder");
        assertThat(reviewView).contains("uploadImage", "reviewOrder", "el-rate");
    }

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path));
    }
}
