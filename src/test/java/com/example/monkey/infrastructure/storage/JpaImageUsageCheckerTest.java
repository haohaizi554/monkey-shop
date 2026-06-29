package com.example.monkey.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.monkey.repository.MonkeyRepository;
import com.example.monkey.repository.OrderRepository;
import com.example.monkey.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class JpaImageUsageCheckerTest {

    @Mock
    private MonkeyRepository monkeyRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private OrderRepository orderRepository;

    @Test
    void reportsUsedWhenProductImageStillReferencesPath() {
        JpaImageUsageChecker checker = new JpaImageUsageChecker(monkeyRepository, userRepository, orderRepository);
        when(monkeyRepository.countByImageUrl("/images/product/used.png")).thenReturn(1L);

        boolean used = checker.isUsed("/images/product/used.png");

        assertThat(used).isTrue();
        verify(monkeyRepository).countByImageUrl("/images/product/used.png");
        verifyNoInteractions(userRepository, orderRepository);
    }

    @Test
    void reportsUsedWhenBuyerAvatarStillReferencesPath() {
        JpaImageUsageChecker checker = new JpaImageUsageChecker(monkeyRepository, userRepository, orderRepository);
        when(monkeyRepository.countByImageUrl("/images/avatar/used.png")).thenReturn(0L);
        when(userRepository.countByAvatar("/images/avatar/used.png")).thenReturn(0L);
        when(orderRepository.countByProductImage("/images/avatar/used.png")).thenReturn(0L);
        when(orderRepository.countByBuyerAvatar("/images/avatar/used.png")).thenReturn(1L);

        boolean used = checker.isUsed("/images/avatar/used.png");

        assertThat(used).isTrue();
        verify(monkeyRepository).countByImageUrl("/images/avatar/used.png");
        verify(userRepository).countByAvatar("/images/avatar/used.png");
        verify(orderRepository).countByProductImage("/images/avatar/used.png");
        verify(orderRepository).countByBuyerAvatar("/images/avatar/used.png");
    }

    @Test
    void reportsUnusedWhenNoPersistedRecordReferencesPath() {
        JpaImageUsageChecker checker = new JpaImageUsageChecker(monkeyRepository, userRepository, orderRepository);
        when(monkeyRepository.countByImageUrl("/images/avatar/free.png")).thenReturn(0L);
        when(userRepository.countByAvatar("/images/avatar/free.png")).thenReturn(0L);
        when(orderRepository.countByProductImage("/images/avatar/free.png")).thenReturn(0L);
        when(orderRepository.countByBuyerAvatar("/images/avatar/free.png")).thenReturn(0L);

        boolean used = checker.isUsed("/images/avatar/free.png");

        assertThat(used).isFalse();
        verify(monkeyRepository).countByImageUrl("/images/avatar/free.png");
        verify(userRepository).countByAvatar("/images/avatar/free.png");
        verify(orderRepository).countByProductImage("/images/avatar/free.png");
        verify(orderRepository).countByBuyerAvatar("/images/avatar/free.png");
    }
}
