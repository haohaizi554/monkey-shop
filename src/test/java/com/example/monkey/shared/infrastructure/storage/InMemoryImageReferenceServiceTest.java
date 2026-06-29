package com.example.monkey.shared.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class InMemoryImageReferenceServiceTest {

    @Test
    void retainsReleasesAndRemovesCountsAtZero() {
        InMemoryImageReferenceService service = new InMemoryImageReferenceService();

        service.retain("/images/product/item.png");
        service.retain("/images/product/item.png");
        service.release("/images/product/item.png");

        assertThat(service.referenceCount("/images/product/item.png")).isEqualTo(1L);

        service.release("/images/product/item.png");

        assertThat(service.referenceCount("/images/product/item.png")).isZero();
    }

    @Test
    void rebuildIgnoresDefaultAssetsAndBlankValues() {
        InMemoryImageReferenceService service = new InMemoryImageReferenceService();
        service.retain("/images/product/stale.png");

        service.rebuild(
                List.of("/images/product/item.png", "/images/product/item.png", "/images/default_product.png", " "));

        assertThat(service.referenceCount("/images/product/stale.png")).isZero();
        assertThat(service.referenceCount("/images/product/item.png")).isEqualTo(2L);
        assertThat(service.referenceCount("/images/default_product.png")).isZero();
    }

    @Test
    void clearRemovesAllReferenceCounts() {
        InMemoryImageReferenceService service = new InMemoryImageReferenceService();
        service.retain("/images/product/item.png");

        service.clear();

        assertThat(service.referenceCount("/images/product/item.png")).isZero();
    }
}
