package com.example.monkey.product.domain;

import java.util.List;
import java.util.Optional;

public interface CatalogStore {

    CatalogSpu save(CatalogSpu spu);

    Optional<CatalogSpu> findSpuById(Long spuId);

    boolean isLeafCategory(Long categoryId);

    List<CategoryNode> findCategoryTree();
}
