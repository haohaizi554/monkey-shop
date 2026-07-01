package com.example.monkey.order.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class OrderImageReferenceSourceTest {

    @Mock
    private OrderRepository orderRepository;

    @Test
    void reportsUsedWhenOrderProductSnapshotReferencesPath() {
        OrderImageReferenceSource source = new OrderImageReferenceSource(orderRepository, 2);
        when(orderRepository.countByProductImage("/images/product/snapshot.png"))
                .thenReturn(1L);

        boolean used = source.isUsed("/images/product/snapshot.png");

        assertThat(used).isTrue();
        verify(orderRepository).countByProductImage("/images/product/snapshot.png");
        verifyNoMoreInteractions(orderRepository);
    }

    @Test
    void reportsUsedWhenOrderBuyerAvatarReferencesPath() {
        OrderImageReferenceSource source = new OrderImageReferenceSource(orderRepository, 2);
        when(orderRepository.countByProductImage("/images/avatar/buyer.png")).thenReturn(0L);
        when(orderRepository.countByBuyerAvatar("/images/avatar/buyer.png")).thenReturn(1L);

        boolean used = source.isUsed("/images/avatar/buyer.png");

        assertThat(used).isTrue();
        verify(orderRepository).countByProductImage("/images/avatar/buyer.png");
        verify(orderRepository).countByBuyerAvatar("/images/avatar/buyer.png");
    }

    @Test
    void scansProductAndBuyerAvatarReferencesInPages() {
        OrderImageReferenceSource source = new OrderImageReferenceSource(orderRepository, 2);
        when(orderRepository.findProductImages(PageRequest.of(0, 2)))
                .thenReturn(List.of("/images/product/momo.png", "/images/product/order-snapshot.png"));
        when(orderRepository.findProductImages(PageRequest.of(1, 2))).thenReturn(List.of());
        when(orderRepository.findBuyerAvatars(PageRequest.of(0, 2))).thenReturn(List.of("/images/avatar/buyer.png"));
        List<String> imagePaths = new ArrayList<>();

        source.forEachReferencedImagePath(imagePaths::add);

        assertThat(imagePaths)
                .containsExactly(
                        "/images/product/momo.png", "/images/product/order-snapshot.png", "/images/avatar/buyer.png");
        verify(orderRepository).findProductImages(PageRequest.of(0, 2));
        verify(orderRepository).findProductImages(PageRequest.of(1, 2));
        verify(orderRepository).findBuyerAvatars(PageRequest.of(0, 2));
    }
}
