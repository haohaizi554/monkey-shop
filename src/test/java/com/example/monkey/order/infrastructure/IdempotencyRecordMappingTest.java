package com.example.monkey.order.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;

class IdempotencyRecordMappingTest {

    @Test
    void idempotencyRecordProtectsOneKeyPerUser() {
        Table table = IdempotencyRecord.class.getAnnotation(Table.class);

        assertThat(table.name()).isEqualTo("idempotency_record");
        assertThat(table.uniqueConstraints()).singleElement().satisfies(unique -> {
            assertThat(unique.name()).isEqualTo("uk_idempotency_user_key");
            assertThat(unique.columnNames()).containsExactly("user_id", "idempotency_key");
        });
        assertThat(IdempotencyRecord.STATUS_PROCESSING).isEqualTo("PROCESSING");
        assertThat(IdempotencyRecord.STATUS_COMPLETED).isEqualTo("COMPLETED");
    }
}
