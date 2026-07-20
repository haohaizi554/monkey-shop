package com.example.monkey.order.infrastructure;

import com.example.monkey.shared.application.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
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

    List<Order> findByCheckoutIdOrderByCheckoutSubOrderIdAsc(Long checkoutId);

    Page<Order> findByUserIdAndPiiAnonymizedFalse(Long userId, Pageable pageable);

    Page<Order> findByUserIdAndUserHiddenFalse(Long userId, Pageable pageable);

    List<Order> findByStatusInAndCreateTimeBeforeAndPiiAnonymizedFalse(
            List<String> statuses, LocalDateTime cutoff, Pageable pageable);

    long countByProductImage(String productImage);

    long countByBuyerAvatar(String buyerAvatar);

    long countByStatus(String status);

    long countByStatusIn(Collection<String> statuses);

    @Query("SELECT SUM(o.price) FROM Order o WHERE o.status <> :refundedStatus")
    BigDecimal sumGmvExcludingStatus(@Param("refundedStatus") String refundedStatus);

    @Query("""
            select year(o.createTime) as bucketYear,
                   month(o.createTime) as bucketMonth,
                   day(o.createTime) as bucketDay,
                   count(o.id) as orderCount,
                   coalesce(sum(case when o.status <> :refundedStatus then o.price else 0.00 end), 0.00) as gmv
            from Order o
            where o.createTime between :startInclusive and :endInclusive
            group by year(o.createTime), month(o.createTime), day(o.createTime)
            """)
    List<OrderTrendProjection> findDailyOrderTrend(
            @Param("startInclusive") LocalDateTime startInclusive,
            @Param("endInclusive") LocalDateTime endInclusive,
            @Param("refundedStatus") String refundedStatus);

    @Query("""
            select year(o.createTime) as bucketYear,
                   month(o.createTime) as bucketMonth,
                   count(o.id) as orderCount,
                   coalesce(sum(case when o.status <> :refundedStatus then o.price else 0.00 end), 0.00) as gmv
            from Order o
            where o.createTime between :startInclusive and :endInclusive
            group by year(o.createTime), month(o.createTime)
            """)
    List<OrderTrendProjection> findMonthlyOrderTrend(
            @Param("startInclusive") LocalDateTime startInclusive,
            @Param("endInclusive") LocalDateTime endInclusive,
            @Param("refundedStatus") String refundedStatus);

    // 闁哄倹婢橀·鍐晬濮橆厾鍙€闁告垹鍎ゆ晶宥夊嫉婢跺寒鍚傞柛妤佹礀閹烩晠鎮¤閸ｇ兘鎯冮崟顐ｇ闁?
    @Query("SELECT o.productImage FROM Order o WHERE o.productImage IS NOT NULL ORDER BY o.id")
    List<String> findProductImages(Pageable pageable);

    @Query("SELECT o.buyerAvatar FROM Order o WHERE o.buyerAvatar IS NOT NULL ORDER BY o.id")
    List<String> findBuyerAvatars(Pageable pageable);

    default int transitionStatus(Long id, String expectedStatus, String nextStatus) {
        return transitionStatus(id, expectedStatus, nextStatus, TenantContext.currentTenantIdOrDefault());
    }

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Order o
            set o.status = :nextStatus,
                o.version = o.version + 1
            where o.id = :id
                and o.tenantId = :tenantId
                and o.status = :expectedStatus
                and o.deleted = false
            """)
    int transitionStatus(
            @Param("id") Long id,
            @Param("expectedStatus") String expectedStatus,
            @Param("nextStatus") String nextStatus,
            @Param("tenantId") Long tenantId);

    default int transitionStatusWithShippingTime(
            Long id, String expectedStatus, String nextStatus, LocalDateTime shippingTime) {
        return transitionStatusWithShippingTime(
                id, expectedStatus, nextStatus, shippingTime, TenantContext.currentTenantIdOrDefault());
    }

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Order o
            set o.status = :nextStatus,
                o.shippingTime = :shippingTime,
                o.version = o.version + 1
            where o.id = :id
                and o.tenantId = :tenantId
                and o.status = :expectedStatus
                and o.deleted = false
            """)
    int transitionStatusWithShippingTime(
            @Param("id") Long id,
            @Param("expectedStatus") String expectedStatus,
            @Param("nextStatus") String nextStatus,
            @Param("shippingTime") LocalDateTime shippingTime,
            @Param("tenantId") Long tenantId);

    interface OrderTrendProjection {
        Integer getBucketYear();

        Integer getBucketMonth();

        Integer getBucketDay();

        Long getOrderCount();

        BigDecimal getGmv();
    }
}
