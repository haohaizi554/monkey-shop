package com.example.monkey.order.infrastructure;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderReviewRepository extends JpaRepository<OrderReviewEntity, Long> {

    boolean existsByOrderIdAndUserIdAndSkuId(Long orderId, Long userId, Long skuId);

    List<OrderReviewEntity> findByOrderIdOrderByCreateTimeDesc(Long orderId);
}
