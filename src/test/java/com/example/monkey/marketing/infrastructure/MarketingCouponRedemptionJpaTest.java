package com.example.monkey.marketing.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.monkey.cart.domain.CartCheckoutStatus;
import com.example.monkey.cart.infrastructure.CartCheckoutEntity;
import com.example.monkey.cart.infrastructure.CartCheckoutRepository;
import com.example.monkey.marketing.domain.CouponStatus;
import com.example.monkey.marketing.domain.CouponType;
import com.example.monkey.shared.infrastructure.privacy.PiiCryptoService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@DataJpaTest(showSql = false)
@ActiveProfiles("test")
@MockitoBean(types = PiiCryptoService.class)
class MarketingCouponRedemptionJpaTest {

    private static final long TENANT_ID = 1L;
    private static final long USER_ID = 7L;
    private static final long CHECKOUT_ID = 101L;
    private static final String COUPON_CODE = "PLATFORM-20";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 1, 1, 0, 0);

    private final TestEntityManager entityManager;
    private final MarketingCouponRepository couponRepository;
    private final MarketingUserCouponRepository userCouponRepository;
    private final CartCheckoutRepository checkoutRepository;

    @Autowired
    MarketingCouponRedemptionJpaTest(
            TestEntityManager entityManager,
            MarketingCouponRepository couponRepository,
            MarketingUserCouponRepository userCouponRepository,
            CartCheckoutRepository checkoutRepository) {
        this.entityManager = entityManager;
        this.couponRepository = couponRepository;
        this.userCouponRepository = userCouponRepository;
        this.checkoutRepository = checkoutRepository;
    }

    @Test
    void couponRedemptionAndReturnUseCheckoutBoundCompareAndSet() {
        couponRepository.save(coupon());
        checkoutRepository.save(checkout());
        userCouponRepository.save(userCoupon());
        entityManager.flush();
        entityManager.clear();

        assertThat(userCouponRepository.redeemClaimedForCheckout(TENANT_ID, USER_ID, COUPON_CODE, CHECKOUT_ID, NOW))
                .isEqualTo(1);
        assertThat(userCouponRepository.redeemClaimedForCheckout(TENANT_ID, USER_ID, COUPON_CODE, CHECKOUT_ID, NOW))
                .isZero();

        MarketingUserCouponEntity redeemed = userCouponRepository
                .findByUserIdAndCouponCode(USER_ID, COUPON_CODE)
                .orElseThrow();
        assertThat(redeemed.getStatus()).isEqualTo(CouponStatus.REDEEMED);
        assertThat(redeemed.getCheckoutId()).isEqualTo(CHECKOUT_ID);
        assertThat(redeemed.getOrderId()).isNull();

        assertThat(userCouponRepository.returnRedeemedForCheckout(TENANT_ID, USER_ID, CHECKOUT_ID + 1))
                .isZero();
        assertThat(userCouponRepository.returnRedeemedForCheckout(TENANT_ID, USER_ID, CHECKOUT_ID))
                .isEqualTo(1);
        assertThat(userCouponRepository.returnRedeemedForCheckout(TENANT_ID, USER_ID, CHECKOUT_ID))
                .isZero();

        MarketingUserCouponEntity returned = userCouponRepository
                .findByUserIdAndCouponCode(USER_ID, COUPON_CODE)
                .orElseThrow();
        assertThat(returned.getStatus()).isEqualTo(CouponStatus.CLAIMED);
        assertThat(returned.getCheckoutId()).isEqualTo(CHECKOUT_ID);
    }

    private static MarketingCouponEntity coupon() {
        MarketingCouponEntity entity = new MarketingCouponEntity();
        entity.setId(1L);
        entity.setCode(COUPON_CODE);
        entity.setName("Platform coupon");
        entity.setType(CouponType.THRESHOLD);
        entity.setThresholdAmount(new BigDecimal("100.00"));
        entity.setDiscountAmount(new BigDecimal("20.00"));
        entity.setDiscountPercent(BigDecimal.ZERO);
        entity.setStackGroup("PLATFORM");
        entity.setTotalQuota(100);
        entity.setClaimedCount(1);
        entity.setStartTime(NOW.minusDays(1));
        entity.setEndTime(NOW.plusDays(1));
        return entity;
    }

    private static CartCheckoutEntity checkout() {
        CartCheckoutEntity entity = new CartCheckoutEntity();
        entity.setId(CHECKOUT_ID);
        entity.setCheckoutNo("CHECKOUT-101");
        entity.setUserId(USER_ID);
        entity.setAddressId(9L);
        entity.setIdempotencyKey("checkout-key-101");
        entity.setOriginalAmount(new BigDecimal("128.00"));
        entity.setDiscountAmount(new BigDecimal("20.00"));
        entity.setPayableAmount(new BigDecimal("108.00"));
        entity.setStatus(CartCheckoutStatus.CHECKED_OUT);
        entity.setProvince("CN-BJ");
        entity.setCreateTime(NOW);
        return entity;
    }

    private static MarketingUserCouponEntity userCoupon() {
        MarketingUserCouponEntity entity = new MarketingUserCouponEntity();
        entity.setId(2L);
        entity.setCouponId(1L);
        entity.setCouponCode(COUPON_CODE);
        entity.setUserId(USER_ID);
        entity.setStatus(CouponStatus.CLAIMED);
        entity.setIdempotencyKey("coupon:claim-checkout");
        entity.setClaimedAt(NOW.minusHours(1));
        return entity;
    }
}
