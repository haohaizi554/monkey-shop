package com.example.monkey.marketing.infrastructure;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MarketingUserCouponRepository extends JpaRepository<MarketingUserCouponEntity, Long> {

    Optional<MarketingUserCouponEntity> findByUserIdAndCouponId(Long userId, Long couponId);

    Optional<MarketingUserCouponEntity> findByCouponCode(String couponCode);
}
