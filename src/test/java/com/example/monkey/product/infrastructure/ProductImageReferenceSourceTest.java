package com.example.monkey.product.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class ProductImageReferenceSourceTest {

    @Mock
    private MonkeyRepository monkeyRepository;

    @Test
    void reportsUsedWhenProductImageReferencesPath() {
        ProductImageReferenceSource source = new ProductImageReferenceSource(monkeyRepository, 2);
        when(monkeyRepository.countByImageUrl("/images/product/used.png")).thenReturn(1L);

        boolean used = source.isUsed("/images/product/used.png");

        assertThat(used).isTrue();
        verify(monkeyRepository).countByImageUrl("/images/product/used.png");
    }

    @Test
    void scansProductImageReferencesInPages() {
        ProductImageReferenceSource source = new ProductImageReferenceSource(monkeyRepository, 2);
        when(monkeyRepository.findImageUrls(PageRequest.of(0, 2)))
                .thenReturn(Arrays.asList("/images/product/momo.png", null));
        when(monkeyRepository.findImageUrls(PageRequest.of(1, 2))).thenReturn(List.of(" "));
        List<String> imagePaths = new ArrayList<>();

        source.forEachReferencedImagePath(imagePaths::add);

        assertThat(imagePaths).containsExactly("/images/product/momo.png");
        verify(monkeyRepository).findImageUrls(PageRequest.of(0, 2));
        verify(monkeyRepository).findImageUrls(PageRequest.of(1, 2));
    }
}
