package com.example.monkey.search.infrastructure;

import com.example.monkey.product.domain.ProductStatus;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface SearchProductSpuRepository extends JpaRepository<SearchProductSpuEntity, Long> {

    List<SearchProductSpuEntity> findByStatusOrderByIdDesc(ProductStatus status, Pageable pageable);
}
