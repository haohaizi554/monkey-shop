package com.example.monkey.product.domain;

import java.util.List;
import java.util.Optional;

public interface CatalogStore {

    CatalogSpu save(CatalogSpu spu);

    Optional<CatalogSpu> findSpuById(Long spuId);

    CatalogPage findManagementPage(CatalogPageRequest request);

    boolean isLeafCategory(Long categoryId);

    List<CategoryNode> findCategoryTree();

    record CatalogPageRequest(int page, int size, ProductStatus status, String keyword) {
        public CatalogPageRequest {
            page = Math.max(0, page);
            size = Math.min(100, Math.max(1, size));
            keyword = keyword == null || keyword.isBlank() ? null : keyword.trim();
        }
    }

    record CatalogPage(List<CatalogSpu> content, int page, int size, long totalElements) {
        public CatalogPage {
            content = content == null ? List.of() : List.copyOf(content);
            page = Math.max(0, page);
            size = Math.max(1, size);
            totalElements = Math.max(0, totalElements);
        }

        public int totalPages() {
            return (int) Math.max(1, Math.ceil((double) totalElements / (double) size));
        }

        public boolean first() {
            return page == 0;
        }

        public boolean last() {
            return page + 1 >= totalPages();
        }
    }
}
