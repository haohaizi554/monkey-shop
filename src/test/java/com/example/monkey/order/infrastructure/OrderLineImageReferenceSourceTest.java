package com.example.monkey.order.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class OrderLineImageReferenceSourceTest {

    @Mock
    private OrderLineRepository orderLineRepository;

    @Test
    void reportsUsedForExactCanonicalImage() {
        OrderLineImageReferenceSource source = new OrderLineImageReferenceSource(orderLineRepository, 2);
        when(orderLineRepository.countByProductImage("/images/product/used.png")).thenReturn(1L);

        boolean used = source.isUsed(" /images/product/used.png ");

        assertThat(used).isTrue();
        verify(orderLineRepository).countByProductImage("/images/product/used.png");
    }

    @Test
    void reportsUsedForCanonicalImageVariantPrefixWhenExactCountIsZero() {
        OrderLineImageReferenceSource source = new OrderLineImageReferenceSource(orderLineRepository, 2);
        when(orderLineRepository.countByProductImage("/images/product/used.png")).thenReturn(0L);
        when(orderLineRepository.countByProductImageStartingWith("/images/product/used.png@"))
                .thenReturn(1L);

        boolean used = source.isUsed(" /images/product/used.png ");

        assertThat(used).isTrue();
        verify(orderLineRepository).countByProductImage("/images/product/used.png");
        verify(orderLineRepository).countByProductImageStartingWith("/images/product/used.png@");
    }

    @Test
    void scansNonBlankLineImagesInPagesWithoutCollapsingMultiplicity() {
        OrderLineImageReferenceSource source = new OrderLineImageReferenceSource(orderLineRepository, 2);
        when(orderLineRepository.findProductImages(PageRequest.of(0, 2)))
                .thenReturn(List.of("/images/product/shared.png", "/images/product/shared.png"));
        when(orderLineRepository.findProductImages(PageRequest.of(1, 2)))
                .thenReturn(List.of(" ", "/images/product/variant.png"));
        when(orderLineRepository.findProductImages(PageRequest.of(2, 2))).thenReturn(List.of());
        List<String> imagePaths = new ArrayList<>();

        source.forEachReferencedImagePath(imagePaths::add);

        assertThat(imagePaths).containsExactly(
                "/images/product/shared.png",
                "/images/product/shared.png",
                "/images/product/variant.png");
        verify(orderLineRepository).findProductImages(PageRequest.of(0, 2));
        verify(orderLineRepository).findProductImages(PageRequest.of(1, 2));
        verify(orderLineRepository).findProductImages(PageRequest.of(2, 2));
    }
}
