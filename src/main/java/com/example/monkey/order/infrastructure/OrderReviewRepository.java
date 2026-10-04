package com.example.monkey.order.infrastructure;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OrderReviewRepository extends JpaRepository<OrderReviewEntity, Long> {

    boolean existsByOrderIdAndUserIdAndSkuId(Long orderId, Long userId, Long skuId);

    List<OrderReviewEntity> findByOrderIdOrderByCreateTimeDesc(Long orderId);

    long countByImageUrlsContaining(String imagePath);

    @Query("SELECT review.imageUrls FROM OrderReviewEntity review WHERE review.imageUrls IS NOT NULL ORDER BY review.id")
    List<String> findImageUrls(Pageable pageable);
}
