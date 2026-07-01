package com.example.monkey.order.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.order.domain.OrderStatus;
import org.junit.jupiter.api.Test;

class JpaPendingOrderCounterTest {

    @Test
    void countsPaidOrdersAsPendingOrders() {
        OrderRepository orderRepository = org.mockito.Mockito.mock(OrderRepository.class);
        when(orderRepository.countByStatus(OrderStatus.PAID.label())).thenReturn(5L);

        JpaPendingOrderCounter counter = new JpaPendingOrderCounter(orderRepository);

        assertThat(counter.countPendingOrders()).isEqualTo(5L);
        verify(orderRepository).countByStatus(OrderStatus.PAID.label());
    }
}
