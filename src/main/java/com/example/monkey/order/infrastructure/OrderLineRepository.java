package com.example.monkey.order.infrastructure;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderLineRepository extends JpaRepository<OrderLineEntity, Long> {

    List<OrderLineEntity> findByOrderIdOrderByIdAsc(Long orderId);
}
