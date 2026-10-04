package com.example.monkey.order.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

class OrderReviewImageReferenceSourceTest {

    @Test
    void splitsNewlineSeparatedReviewImagesAndScansAllPages() {
        OrderReviewRepository repository = mock(OrderReviewRepository.class);
        when(repository.findImageUrls(PageRequest.of(0, 2)))
                .thenReturn(List.of(" /images/avatar/review-a.png\n/images/avatar/review-b.png ", ""));
        when(repository.findImageUrls(PageRequest.of(1, 2))).thenReturn(List.of("/images/avatar/review-c.png"));
        OrderReviewImageReferenceSource source = new OrderReviewImageReferenceSource(repository, 2);
        List<String> paths = new ArrayList<>();

        source.forEachReferencedImagePath(paths::add);

        assertThat(paths)
                .containsExactly(
                        "/images/avatar/review-a.png", "/images/avatar/review-b.png", "/images/avatar/review-c.png");
    }
}
