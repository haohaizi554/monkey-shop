package com.example.monkey.product.infrastructure;

import com.example.monkey.product.domain.ProductStatus;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductSpuRepository extends JpaRepository<ProductSpu, Long> {

    List<ProductSpu> findByStatusOrderByIdDesc(ProductStatus status, Pageable pageable);
}
