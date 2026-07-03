package com.example.monkey.product.domain;

import java.util.List;
import java.util.Optional;

public interface CategoryTreeCache {

    Optional<List<CategoryNode>> get();

    void put(List<CategoryNode> categoryTree);

    void evict();
}
