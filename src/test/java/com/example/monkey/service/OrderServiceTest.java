package com.example.monkey.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.entity.Order;
import com.example.monkey.repository.OrderRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderService();
        ReflectionTestUtils.setField(orderService, "orderRepository", orderRepository);
    }

    @Test
    void updateStatusForOwnerRejectsAnotherUsersOrder() {
        Order order = new Order();
        order.setId(10L);
        order.setUserId(99L);
        order.setStatus("DONE");
        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));

        String result = orderService.updateStatusForOwner(10L, 42L, "RETURN_REQUESTED", "DONE");

        assertThat(result).startsWith("error:");
        assertThat(order.getStatus()).isEqualTo("DONE");
        verify(orderRepository, never()).save(order);
    }

    @Test
    void updateStatusForOwnerChangesOwnedOrderWhenStateMatches() {
        Order order = new Order();
        order.setId(10L);
        order.setUserId(42L);
        order.setStatus("DONE");
        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));

        String result = orderService.updateStatusForOwner(10L, 42L, "RETURN_REQUESTED", "DONE");

        assertThat(result).isEqualTo("ok");
        assertThat(order.getStatus()).isEqualTo("RETURN_REQUESTED");
        verify(orderRepository).save(order);
    }
}
