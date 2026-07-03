package com.example.monkey.product.infrastructure;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductCategoryRepository extends JpaRepository<ProductCategory, Long> {

    List<ProductCategory> findByActiveTrueOrderByLevelAscSortOrderAscNameAsc();
}
