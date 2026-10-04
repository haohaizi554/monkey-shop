package com.example.monkey.cart.infrastructure;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CartCheckoutLineRepository extends JpaRepository<CartCheckoutLineEntity, Long> {

    List<CartCheckoutLineEntity> findByCheckoutIdOrderBySubOrderIdAscIdAsc(Long checkoutId);

    long countByProductImage(String productImage);

    long countByProductImageStartingWith(String productImagePrefix);

    @Query("SELECT line.productImage FROM CartCheckoutLineEntity line "
            + "WHERE line.productImage IS NOT NULL ORDER BY line.id")
    List<String> findProductImages(Pageable pageable);
}
