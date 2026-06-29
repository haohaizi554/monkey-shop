package com.example.monkey.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.monkey.order.domain.OrderOwnershipChecker;
import org.junit.jupiter.api.Test;

class OrderOwnershipServiceTest {

    private final OrderOwnershipChecker orderOwnershipChecker = org.mockito.Mockito.mock(OrderOwnershipChecker.class);
    private final OrderOwnershipService orderOwnershipService = new OrderOwnershipService(orderOwnershipChecker);

    @Test
    void delegatesVisibleOwnershipToDomainPort() {
        when(orderOwnershipChecker.isVisibleOwner(42L, 7L)).thenReturn(true);

        assertThat(orderOwnershipService.isVisibleOwner(42L, 7L)).isTrue();

        verify(orderOwnershipChecker).isVisibleOwner(42L, 7L);
    }

    @Test
    void rejectsIncompleteOwnershipInputsWithoutQueryingDomainPort() {
        assertThat(orderOwnershipService.isVisibleOwner(null, 7L)).isFalse();
        assertThat(orderOwnershipService.isVisibleOwner(42L, null)).isFalse();

        verifyNoInteractions(orderOwnershipChecker);
    }
}
