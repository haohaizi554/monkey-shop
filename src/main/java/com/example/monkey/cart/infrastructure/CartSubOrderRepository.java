package com.example.monkey.cart.infrastructure;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CartSubOrderRepository extends JpaRepository<CartSubOrderEntity, Long> {

    List<CartSubOrderEntity> findByCheckoutIdOrderByIdAsc(Long checkoutId);
}
