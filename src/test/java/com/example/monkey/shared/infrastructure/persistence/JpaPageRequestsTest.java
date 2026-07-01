package com.example.monkey.shared.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

class JpaPageRequestsTest {

    @Test
    void boundsPageAndSizeWhilePreservingSort() {
        Sort sort = Sort.by(Sort.Direction.DESC, "createdAt");

        PageRequest request = JpaPageRequests.bounded(-4, 500, sort);

        assertThat(request.getPageNumber()).isZero();
        assertThat(request.getPageSize()).isEqualTo(100);
        assertThat(request.getSort()).isEqualTo(sort);
    }

    @Test
    void enforcesMinimumSizeWithoutChangingValidPage() {
        PageRequest request = JpaPageRequests.bounded(3, 0, Sort.unsorted());

        assertThat(request.getPageNumber()).isEqualTo(3);
        assertThat(request.getPageSize()).isEqualTo(1);
    }
}
