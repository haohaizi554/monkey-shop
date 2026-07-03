package com.example.monkey.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class Ws2InventoryWorkflowTest {

    @Test
    void inventoryArtifactsWireMultiWarehouseStockToLocksAndSchedulers() throws IOException {
        String docs = read("docs/inventory/ws2.md");
        String service =
                read("src/main/java/com/example/monkey/inventory/application/InventoryApplicationService.java");
        String controller = read("src/main/java/com/example/monkey/inventory/interfaces/InventoryController.java");
        String lock =
                read("src/main/java/com/example/monkey/inventory/infrastructure/RedissonInventoryLockManager.java");
        String store = read("src/main/java/com/example/monkey/inventory/infrastructure/JpaInventoryStore.java");
        String rateLimit = read("src/main/java/com/example/monkey/shared/interfaces/security/ApiRateLimitFilter.java");

        assertThat(docs)
                .contains("available + locked + deducted + in_transit")
                .contains("V22")
                .contains("V23");
        assertThat(service)
                .contains("@Transactional")
                .contains("@Transactional(readOnly = true)")
                .contains("@WithSpan(\"inventory.reserve\")")
                .contains("idGenerator.nextId()")
                .contains("AuditService.INVENTORY_RESERVED")
                .contains("@SchedulerLock(")
                .contains("name = \"inventory-release-expired-reservations\"");
        assertThat(controller)
                .contains("@RequestMapping({\"/api/inventory\", \"/api/v1/inventory\"})")
                .contains("hasAuthority('ORDER_CREATE')")
                .contains("hasAuthority('ORDER_MANAGE')");
        assertThat(lock)
                .contains("inventory:sku:")
                .contains("tryLock(WAIT_TIME.toMillis(), LEASE_TIME.toMillis(), TimeUnit.MILLISECONDS)");
        assertThat(store).contains("implements InventoryStore").contains("reconcile()");
        assertThat(rateLimit).contains("path.startsWith(\"/api/inventory\")").contains("ApiRateLimitOperation.SEARCH");
    }

    @Test
    void migrationsDefineInventorySchemaWithUniquenessAndForeignKeys() throws IOException {
        String warehouse = read("src/main/resources/db/migration/V22__inventory_multi_warehouse.sql");
        String ledger = read("src/main/resources/db/migration/V23__inventory_stock_ledger.sql");

        assertThat(warehouse)
                .contains("CREATE TABLE inventory_warehouse")
                .contains("CREATE TABLE inventory_stock")
                .contains("CONSTRAINT uk_inventory_stock_sku_warehouse UNIQUE")
                .contains("CONSTRAINT fk_inventory_stock_sku FOREIGN KEY")
                .contains("CONSTRAINT ck_inventory_stock_non_negative");
        assertThat(ledger)
                .contains("CREATE TABLE inventory_reservation")
                .contains("CREATE TABLE inventory_stock_ledger")
                .contains("CONSTRAINT uk_inventory_reservation_key UNIQUE")
                .contains("CONSTRAINT uk_inventory_ledger_idempotency UNIQUE")
                .contains("KEY idx_inventory_reservation_expiry");
    }

    @Test
    void inventoryDomainStaysFrameworkFree() throws IOException {
        List<String> violations = new ArrayList<>();
        List<String> forbidden = List.of("org.springframework", "jakarta.persistence", "org.hibernate");

        try (var paths = Files.walk(Path.of("src/main/java/com/example/monkey/inventory/domain"))) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String content = Files.readString(path, StandardCharsets.UTF_8);
                for (String pattern : forbidden) {
                    if (content.contains(pattern)) {
                        violations.add(path.normalize().toString().replace('\\', '/') + " contains " + pattern);
                    }
                }
            }
        }

        assertThat(violations).isEmpty();
    }

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
