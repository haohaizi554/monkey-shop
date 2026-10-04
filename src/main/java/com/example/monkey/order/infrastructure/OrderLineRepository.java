package com.example.monkey.order.infrastructure;

import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OrderLineRepository extends JpaRepository<OrderLineEntity, Long> {

    List<OrderLineEntity> findByOrderIdOrderByIdAsc(Long orderId);

    List<OrderLineEntity> findByOrderIdInOrderByOrderIdAscIdAsc(Collection<Long> orderIds);

    long countByProductImage(String productImage);

    long countByProductImageStartingWith(String productImagePrefix);

    @Query("""
            SELECT line.productImage
            FROM OrderLineEntity line
            WHERE line.productImage IS NOT NULL
              AND TRIM(line.productImage) <> ''
            ORDER BY line.id
            """)
    List<String> findProductImages(Pageable pageable);
}
