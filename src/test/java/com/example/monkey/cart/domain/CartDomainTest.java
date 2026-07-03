package com.example.monkey.cart.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class CartDomainTest {

    @Test
    void upsertMergesQuantityAndSelectionBySku() {
        LocalDateTime now = LocalDateTime.parse("2026-01-01T00:00:00");
        CartSnapshot cart = new CartSnapshot(7L, null)
                .upsert(1001L, 1L, 2, true, now)
                .upsert(1001L, 1L, 3, false, now.plusMinutes(1));

        assertThat(cart.items()).hasSize(1);
        assertThat(cart.items().get(0).quantity()).isEqualTo(5);
        assertThat(cart.items().get(0).selected()).isFalse();
        assertThat(cart.selectedItems()).isEmpty();
    }

    @Test
    void quantityIsBounded() {
        LocalDateTime now = LocalDateTime.parse("2026-01-01T00:00:00");

        assertThatThrownBy(() -> new CartItem(1001L, 1L, 0, true, now, now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("quantity");
        assertThatThrownBy(() -> new CartItem(1001L, 1L, 1_000, true, now, now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("quantity");
    }
}
