package com.example.monkey.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.monkey.order.infrastructure.Order;
import com.example.monkey.product.infrastructure.Monkey;
import jakarta.persistence.Column;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class MoneyPrecisionMappingTest {

    @Test
    void productAndOrderPricesUseBigDecimalWithDatabaseScale() throws NoSuchFieldException {
        assertDecimalPriceColumn(Monkey.class);
        assertDecimalPriceColumn(Order.class);
    }

    @Test
    void productionMoneyCodeDoesNotReintroduceFloatingPointAmounts() throws IOException {
        Pattern floatingPointPrice = Pattern.compile("\\b(?:Double|double)\\s+price\\b");
        Pattern unsafeBigDecimalConstruction = Pattern.compile("\\bnew\\s+BigDecimal\\s*\\(");

        try (var files = Files.walk(Path.of("src/main/java"))) {
            assertThat(files.filter(Files::isRegularFile)
                            .filter(path -> path.toString().endsWith(".java"))
                            .filter(path -> productionSourceMatches(path, floatingPointPrice)
                                    || productionSourceMatches(path, unsafeBigDecimalConstruction)))
                    .isEmpty();
        }
    }

    @Test
    void flywayMigrationConvertsLegacyPriceColumnsToDecimal() throws IOException {
        String migration = Files.readString(
                Path.of("src/main/resources/db/migration/V3__order_price_and_product_snapshot.sql"),
                StandardCharsets.UTF_8);

        assertThat(migration).containsPattern("ALTER TABLE `monkey`\\s+MODIFY COLUMN `price` DECIMAL\\(10, 2\\)");
        assertThat(migration).containsPattern("ALTER TABLE `orders`[\\s\\S]*MODIFY COLUMN `price` DECIMAL\\(10, 2\\)");
    }

    private static void assertDecimalPriceColumn(Class<?> entityType) throws NoSuchFieldException {
        var price = entityType.getDeclaredField("price");
        Column column = price.getAnnotation(Column.class);

        assertThat(price.getType()).isEqualTo(BigDecimal.class);
        assertThat(column).isNotNull();
        assertThat(column.precision()).isEqualTo(10);
        assertThat(column.scale()).isEqualTo(2);
    }

    private static boolean productionSourceMatches(Path path, Pattern pattern) {
        try {
            return pattern.matcher(Files.readString(path, StandardCharsets.UTF_8))
                    .find();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to inspect " + path, e);
        }
    }
}
