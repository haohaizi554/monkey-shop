package com.example.monkey.order.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Table;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class IdempotencyRecordMappingTest {

    @Test
    void idempotencyRecordProtectsOneKeyPerTenantAndUser() {
        Table table = IdempotencyRecord.class.getAnnotation(Table.class);

        assertThat(table.name()).isEqualTo("idempotency_record");
        assertThat(table.uniqueConstraints()).singleElement().satisfies(unique -> {
            assertThat(unique.name()).isEqualTo("uk_idempotency_user_key");
            assertThat(unique.columnNames()).containsExactly("tenant_id", "user_id", "idempotency_key");
        });
        assertThat(IdempotencyRecord.STATUS_PROCESSING).isEqualTo("PROCESSING");
        assertThat(IdempotencyRecord.STATUS_COMPLETED).isEqualTo("COMPLETED");
    }

    @Test
    void migrationExpandsHistoricalIdempotencyUniquenessToTenantScope() throws IOException {
        Path migration =
                Path.of("src/main/resources/db/migration/V59__tenant_scope_order_idempotency.sql");

        assertThat(migration).exists();
        assertThat(Files.readString(migration, StandardCharsets.UTF_8))
                .contains("DROP INDEX uk_idempotency_user_key")
                .contains("UNIQUE (tenant_id, user_id, idempotency_key)");
    }
}
