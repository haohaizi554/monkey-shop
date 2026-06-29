package com.example.monkey.repository;

import com.example.monkey.entity.Order;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Long> {
    java.util.Optional<Order> findByIdAndUserIdAndUserHiddenFalse(Long id, Long userId);

    boolean existsByIdAndUserIdAndUserHiddenFalse(Long id, Long userId);

    List<Order> findByUserId(Long userId);

    List<Order> findByUserIdAndUserHiddenFalseOrderByCreateTimeDesc(Long userId);

    Page<Order> findByUserIdAndUserHiddenFalse(Long userId, Pageable pageable);

    List<Order> findByStatusInAndCreateTimeBefore(List<String> statuses, LocalDateTime cutoff);

    long countByProductImage(String productImage);

    long countByBuyerAvatar(String buyerAvatar);

    long countByStatus(String status);

    // 新增：查出所有订单快照里的图片
    @Query("SELECT o.productImage FROM Order o WHERE o.productImage IS NOT NULL")
    List<String> findAllProductImages();

    @Query("SELECT o.buyerAvatar FROM Order o WHERE o.buyerAvatar IS NOT NULL")
    List<String> findAllBuyerAvatars();

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Order o
            set o.status = :nextStatus,
                o.version = o.version + 1
            where o.id = :id
                and o.status = :expectedStatus
                and o.deleted = false
                and o.userHidden = false
            """)
    int transitionStatus(
            @Param("id") Long id,
            @Param("expectedStatus") String expectedStatus,
            @Param("nextStatus") String nextStatus);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Order o
            set o.status = :nextStatus,
                o.shippingTime = :shippingTime,
                o.version = o.version + 1
            where o.id = :id
                and o.status = :expectedStatus
                and o.deleted = false
                and o.userHidden = false
            """)
    int transitionStatusWithShippingTime(
            @Param("id") Long id,
            @Param("expectedStatus") String expectedStatus,
            @Param("nextStatus") String nextStatus,
            @Param("shippingTime") LocalDateTime shippingTime);
}
