package com.example.monkey.order.application.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.monkey.order.domain.OrderStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

class OrderPageQueryTest {

    @Test
    void normalizesStatusNamesAndLabelsForStoredOrderCompatibility() {
        OrderPageQuery query =
                new OrderPageQuery(0, 20, List.of(), List.of(" PAID ", OrderStatus.SHIPPED.label(), "PAID"), " Momo ");

        assertThat(query.statuses())
                .containsExactly(
                        OrderStatus.PAID.name(),
                        OrderStatus.PAID.label(),
                        OrderStatus.SHIPPED.name(),
                        OrderStatus.SHIPPED.label());
        assertThat(query.keyword()).isEqualTo("Momo");
    }

    @Test
    void treatsBlankFiltersAsAbsent() {
        OrderPageQuery query = new OrderPageQuery(0, 20, null, List.of(" "), " ");

        assertThat(query.sortOrders()).isEmpty();
        assertThat(query.statuses()).isEmpty();
        assertThat(query.keyword()).isNull();
    }
}
