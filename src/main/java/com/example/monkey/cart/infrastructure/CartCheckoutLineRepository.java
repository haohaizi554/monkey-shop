package com.example.monkey.cart.infrastructure;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CartCheckoutLineRepository extends JpaRepository<CartCheckoutLineEntity, Long> {

    List<CartCheckoutLineEntity> findByCheckoutIdOrderBySubOrderIdAscIdAsc(Long checkoutId);
}
