package com.example.monkey.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SchemaMigrationTest {

    @Test
    void flywayIsBoundToValidatedJpaSchema() throws IOException {
        String pom = read("pom.xml");
        String application = read("src/main/resources/application.yml");

        assertThat(pom).contains("<artifactId>flyway-core</artifactId>");
        assertThat(pom).contains("<artifactId>flyway-mysql</artifactId>");
        assertThat(application).contains("flyway:");
        assertThat(application).contains("locations: classpath:db/migration");
        assertThat(application).contains("ddl-auto: validate");
    }

    @Test
    void migrationsEndWithDecimalMoneyAndProductSnapshotColumns() throws IOException {
        String v1 = read("src/main/resources/db/migration/V1__init_schema.sql");
        String v2 = read("src/main/resources/db/migration/V2__add_lookup_indexes.sql");
        String v3 = read("src/main/resources/db/migration/V3__order_price_and_product_snapshot.sql");

        assertThat(v1).contains("CREATE TABLE IF NOT EXISTS `orders`");
        assertThat(v1).contains("`price` DOUBLE");
        assertThat(v2).contains("idx_orders_user_id_create_time");
        assertThat(v3).contains("ADD COLUMN `product_id` BIGINT");
        assertThat(v3).contains("MODIFY COLUMN `price` DECIMAL(10, 2)");
        assertThat(v3).contains("uk_orders_order_no");
    }

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }
}
