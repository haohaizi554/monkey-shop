package com.example.monkey.shared.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

class JpaSortsTest {

    @Test
    void createsOrderOnlyForAllowedProperty() {
        Sort.Order order = JpaSorts.allowedOrder("createdAt", Sort.Direction.DESC, Set.of("createdAt", "id"))
                .orElseThrow();

        assertThat(order.getProperty()).isEqualTo("createdAt");
        assertThat(order.getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void rejectsBlankOrDisallowedProperties() {
        assertThat(JpaSorts.allowedOrder(" ", Sort.Direction.ASC, Set.of("id"))).isEmpty();
        assertThat(JpaSorts.allowedOrder("price", Sort.Direction.ASC, Set.of("id")))
                .isEmpty();
    }
}
