package com.example.monkey.order.infrastructure;

import com.example.monkey.order.domain.OrderShipmentStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderShipmentBatchRepository extends JpaRepository<OrderShipmentBatchEntity, Long> {

    List<OrderShipmentBatchEntity> findByOrderIdOrderByShippedAtAsc(Long orderId);

    List<OrderShipmentBatchEntity> findTop100ByStatusAndShippedAtBeforeOrderByShippedAtAsc(
            OrderShipmentStatus status, LocalDateTime shippedBefore);
}
