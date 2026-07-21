package com.example.monkey.product.infrastructure;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductSkuRepository extends JpaRepository<ProductSku, Long> {

    List<ProductSku> findBySpuIdAndActiveTrueOrderByIdAsc(Long spuId);
}
