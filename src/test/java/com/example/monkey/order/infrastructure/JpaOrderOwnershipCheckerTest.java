package com.example.monkey.order.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class JpaOrderOwnershipCheckerTest {

    @Mock
    private OrderRepository orderRepository;

    @Test
    void checksVisibleOrderOwnershipThroughRepository() {
        JpaOrderOwnershipChecker checker = new JpaOrderOwnershipChecker(orderRepository);
        when(orderRepository.existsByIdAndUserIdAndUserHiddenFalse(42L, 7L)).thenReturn(true);

        assertThat(checker.isVisibleOwner(42L, 7L)).isTrue();

        verify(orderRepository).existsByIdAndUserIdAndUserHiddenFalse(42L, 7L);
    }
}
