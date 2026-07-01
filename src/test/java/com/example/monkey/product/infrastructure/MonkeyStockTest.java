package com.example.monkey.product.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import org.junit.jupiter.api.Test;

class MonkeyStockTest {

    @Test
    void deductStockDecrementsAvailableStock() {
        Monkey monkey = new Monkey();
        monkey.setStock(2);

        monkey.deductStock();

        assertThat(monkey.getStock()).isEqualTo(1);
        assertThat(monkey.hasStock()).isTrue();
    }

    @Test
    void deductStockRejectsEmptyStock() {
        Monkey monkey = new Monkey();
        monkey.setStock(0);

        assertThatIllegalStateException().isThrownBy(monkey::deductStock).withMessage(Monkey.STOCK_NOT_AVAILABLE);

        assertThat(monkey.getStock()).isZero();
    }

    @Test
    void restoreStockIncrementsExistingStockAndInitializesMissingStock() {
        Monkey monkey = new Monkey();
        monkey.setStock(2);
        monkey.restoreStock();
        assertThat(monkey.getStock()).isEqualTo(3);

        Monkey missingStock = new Monkey();
        missingStock.restoreStock();
        assertThat(missingStock.getStock()).isEqualTo(1);
    }
}
