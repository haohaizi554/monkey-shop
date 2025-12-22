package com.example.monkey.repository;

import com.example.monkey.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface OrderRepository extends JpaRepository<Order, Long> {
    List<Order> findByUserIdOrderByCreateTimeDesc(Long userId);
    long countByProductImage(String productImage);
    long countByBuyerAvatar(String buyerAvatar);

    // 新增：查出所有订单快照里的图片
    @Query("SELECT o.productImage FROM Order o WHERE o.productImage IS NOT NULL")
    List<String> findAllProductImages();

    @Query("SELECT o.buyerAvatar FROM Order o WHERE o.buyerAvatar IS NOT NULL")
    List<String> findAllBuyerAvatars();
}