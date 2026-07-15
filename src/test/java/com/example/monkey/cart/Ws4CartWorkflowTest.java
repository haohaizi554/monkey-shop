package com.example.monkey.cart;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class Ws4CartWorkflowTest {

    @Test
    void cartArtifactsWireRedisSplitCheckoutInventoryAndMarketing() throws IOException {
        String docs = read("docs/cart/ws4.md");
        String migration = read("src/main/resources/db/migration/V27__cart.sql");
        String cleanupMigration = read("src/main/resources/db/migration/V52__durable_cart_checkout_cleanup.sql");
        String service = read("src/main/java/com/example/monkey/cart/application/CartApplicationService.java");
        String cleanupScheduler =
                read("src/main/java/com/example/monkey/cart/application/DurableCartCleanupScheduler.java");
        String cleanupWorker = read("src/main/java/com/example/monkey/cart/application/CartCleanupRetryWorker.java");
        String cleanupTenantSource =
                read("src/main/java/com/example/monkey/cart/infrastructure/JdbcCartCleanupTenantSource.java");
        String controller = read("src/main/java/com/example/monkey/cart/interfaces/CartController.java");
        String redisStore = read("src/main/java/com/example/monkey/cart/infrastructure/RedisCartStore.java");
        String lock = read("src/main/java/com/example/monkey/cart/infrastructure/RedissonCartLockManager.java");
        String rateLimit = read("src/main/java/com/example/monkey/shared/interfaces/security/ApiRateLimitFilter.java");
        String frontendApi = read("frontend/src/api/cart.ts");
        String cartView = read("frontend/src/views/CartView.vue");
        String checkoutView = read("frontend/src/views/CheckoutView.vue");

        assertThat(docs).contains("cross-shop cart", "Idempotency-Key");
        assertThat(migration).contains("CREATE TABLE cart_checkout", "CREATE TABLE cart_sub_order");
        assertThat(migration).contains("CREATE TABLE cart_checkout_line", "uk_cart_checkout_user_idempotency");
        assertThat(cleanupMigration).contains("request_fingerprint", "CREATE TABLE cart_cleanup_intent");
        assertThat(service).contains("InventoryApplicationService", "MarketingApplicationService");
        assertThat(service).contains("@WithSpan(\"cart.checkout\")", "cartCleanupScheduler.schedule");
        assertThat(cleanupScheduler).contains("intentStore.save", "afterCommit");
        assertThat(cleanupWorker).contains("@Scheduled", "findReadyCheckoutIds");
        assertThat(cleanupTenantSource).contains("SELECT DISTINCT tenant_id", "next_attempt_at <= ?");
        assertThat(controller).contains("@RequestMapping({\"/api/cart\", \"/api/v1/cart\"})");
        assertThat(redisStore).contains("cart:tenant:", ":user:", "opsForHash()");
        assertThat(lock).contains("cart:checkout:", "tryLock");
        assertThat(rateLimit).contains("ApiRateLimitOperation.CART", "isCartPath", "startsWith(\"/api/cart/\")");
        assertThat(frontendApi).contains("checkoutCart", "Idempotency-Key");
        assertThat(cartView).contains("addCartItem", "selectCartItem");
        assertThat(checkoutView).contains("previewCartCheckout", "checkoutCart");
    }

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path));
    }
}
