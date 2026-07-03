package com.example.monkey.marketing.infrastructure;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MarketingCouponRepository extends JpaRepository<MarketingCouponEntity, Long> {

    Optional<MarketingCouponEntity> findByCode(String code);
}
