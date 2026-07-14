package com.example.monkey.marketing.infrastructure;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MarketingUserCouponRepository extends JpaRepository<MarketingUserCouponEntity, Long> {

    Optional<MarketingUserCouponEntity> findByUserIdAndCouponId(Long userId, Long couponId);

    Optional<MarketingUserCouponEntity> findByUserIdAndCouponCode(Long userId, String couponCode);

    List<MarketingUserCouponEntity> findTop20ByUserIdOrderByClaimedAtDesc(Long userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
                    UPDATE marketing_user_coupon
                    SET status = 'REDEEMED', order_id = :orderId, checkout_id = NULL, used_at = :usedAt
                    WHERE tenant_id = :tenantId
                      AND user_id = :userId
                      AND coupon_code = :couponCode
                      AND status = 'CLAIMED'
                      AND coupon_id IN (
                          SELECT id FROM marketing_coupon
                          WHERE tenant_id = :tenantId
                            AND code = :couponCode
                            AND start_time <= :usedAt
                            AND end_time > :usedAt
                      )
                    """, nativeQuery = true)
    int redeemClaimedForOrder(
            @Param("tenantId") Long tenantId,
            @Param("userId") Long userId,
            @Param("couponCode") String couponCode,
            @Param("orderId") Long orderId,
            @Param("usedAt") LocalDateTime usedAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
                    UPDATE marketing_user_coupon
                    SET status = 'CLAIMED'
                    WHERE tenant_id = :tenantId
                      AND user_id = :userId
                      AND coupon_code = :couponCode
                      AND status IN ('REDEEMED', 'USED')
                      AND order_id = :orderId
                      AND checkout_id IS NULL
                    """, nativeQuery = true)
    int returnRedeemedForOrder(
            @Param("tenantId") Long tenantId,
            @Param("userId") Long userId,
            @Param("couponCode") String couponCode,
            @Param("orderId") Long orderId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
                    UPDATE marketing_user_coupon
                    SET status = 'REDEEMED', checkout_id = :checkoutId, order_id = NULL, used_at = :usedAt
                    WHERE tenant_id = :tenantId
                      AND user_id = :userId
                      AND coupon_code = :couponCode
                      AND status = 'CLAIMED'
                      AND coupon_id IN (
                          SELECT id FROM marketing_coupon
                          WHERE tenant_id = :tenantId
                            AND code = :couponCode
                            AND start_time <= :usedAt
                            AND end_time > :usedAt
                      )
                      AND EXISTS (
                          SELECT 1 FROM cart_checkout
                          WHERE id = :checkoutId
                            AND tenant_id = :tenantId
                            AND user_id = :userId
                      )
                    """, nativeQuery = true)
    int redeemClaimedForCheckout(
            @Param("tenantId") Long tenantId,
            @Param("userId") Long userId,
            @Param("couponCode") String couponCode,
            @Param("checkoutId") Long checkoutId,
            @Param("usedAt") LocalDateTime usedAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
                    UPDATE marketing_user_coupon
                    SET status = 'CLAIMED'
                    WHERE tenant_id = :tenantId
                      AND user_id = :userId
                      AND checkout_id = :checkoutId
                      AND status IN ('REDEEMED', 'USED')
                    """, nativeQuery = true)
    int returnRedeemedForCheckout(
            @Param("tenantId") Long tenantId, @Param("userId") Long userId, @Param("checkoutId") Long checkoutId);
}
