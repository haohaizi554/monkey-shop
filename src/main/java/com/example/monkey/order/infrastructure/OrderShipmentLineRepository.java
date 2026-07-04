package com.example.monkey.order.infrastructure;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderShipmentLineRepository extends JpaRepository<OrderShipmentLineEntity, Long> {

    List<OrderShipmentLineEntity> findByShipmentId(Long shipmentId);

    List<OrderShipmentLineEntity> findByShipmentIdIn(Collection<Long> shipmentIds);
}
