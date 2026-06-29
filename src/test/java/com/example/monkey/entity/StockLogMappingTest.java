package com.example.monkey.entity;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;

class StockLogMappingTest {

    @Test
    void stockLogProtectsOneLedgerEntryPerOrderAndDirection() {
        Table table = StockLog.class.getAnnotation(Table.class);

        assertThat(table.name()).isEqualTo("stock_log");
        assertThat(table.uniqueConstraints()).singleElement().satisfies(unique -> {
            assertThat(unique.name()).isEqualTo("uk_stock_log_order_direction");
            assertThat(unique.columnNames()).containsExactly("order_id", "direction");
        });
        assertThat(StockLog.DIRECTION_RESTORE).isEqualTo("RESTORE");
    }
}
