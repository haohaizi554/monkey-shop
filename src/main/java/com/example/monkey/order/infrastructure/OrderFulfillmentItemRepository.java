package com.example.monkey.order.infrastructure;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderFulfillmentItemRepository extends JpaRepository<OrderFulfillmentItemEntity, Long> {

    List<OrderFulfillmentItemEntity> findByOrderIdOrderBySkuIdAsc(Long orderId);

    Optional<OrderFulfillmentItemEntity> findByOrderIdAndSkuId(Long orderId, Long skuId);
}
