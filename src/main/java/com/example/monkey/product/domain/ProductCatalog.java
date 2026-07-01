package com.example.monkey.product.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface ProductCatalog {

    ProductPage findPage(ProductPageRequest request);

    ProductRecord save(ProductRecord product);

    Optional<ProductRecord> findById(Long id);

    void deleteById(Long id);

    record ProductRecord(
            Long id, String name, String breed, BigDecimal price, String description, String imageUrl, Integer stock) {}

    record ProductPageRequest(int page, int size, List<SortOrder> sortOrders) {
        public ProductPageRequest {
            sortOrders = sortOrders == null ? List.of() : List.copyOf(sortOrders);
        }
    }

    record ProductPage(
            List<ProductRecord> content,
            int page,
            int size,
            long totalElements,
            int totalPages,
            boolean first,
            boolean last) {
        public ProductPage {
            content = content == null ? List.of() : List.copyOf(content);
        }
    }

    record SortOrder(String property, Direction direction) {
        public enum Direction {
            ASC,
            DESC
        }
    }
}
