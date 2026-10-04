package com.example.monkey.product.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

class ProductSpuImageReferenceSourceTest {

    @Test
    void scansAllModernSpuImageUrlsInPages() {
        ProductSpuRepository repository = mock(ProductSpuRepository.class);
        when(repository.findImageUrls(PageRequest.of(0, 2)))
                .thenReturn(List.of("/images/product/spu-a.png", " /images/product/spu-b.png "));
        when(repository.findImageUrls(PageRequest.of(1, 2))).thenReturn(List.of("/images/product/spu-c.png"));
        ProductSpuImageReferenceSource source = new ProductSpuImageReferenceSource(repository, 2);
        List<String> paths = new ArrayList<>();

        source.forEachReferencedImagePath(paths::add);

        assertThat(paths)
                .containsExactly("/images/product/spu-a.png", "/images/product/spu-b.png", "/images/product/spu-c.png");
    }
}
