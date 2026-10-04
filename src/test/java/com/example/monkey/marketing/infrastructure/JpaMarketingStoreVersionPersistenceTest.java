package com.example.monkey.marketing.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.monkey.marketing.domain.CouponDefinition;
import com.example.monkey.marketing.domain.CouponType;
import com.example.monkey.marketing.domain.GroupBuyStatus;
import com.example.monkey.marketing.domain.GroupBuyTeam;
import com.example.monkey.marketing.domain.SeckillActivity;
import com.example.monkey.shared.infrastructure.privacy.PiiCryptoService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@DataJpaTest(showSql = false)
@ActiveProfiles("test")
@MockitoBean(types = PiiCryptoService.class)
class JpaMarketingStoreVersionPersistenceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 1, 1, 0, 0);

    private final TestEntityManager entityManager;
    private final MarketingCouponRepository couponRepository;
    private final MarketingSeckillActivityRepository seckillActivityRepository;
    private final MarketingGroupBuyTeamRepository groupBuyTeamRepository;
    private final JpaMarketingStore store;

    @Autowired
    JpaMarketingStoreVersionPersistenceTest(
            TestEntityManager entityManager,
            MarketingCouponRepository couponRepository,
            MarketingUserCouponRepository userCouponRepository,
            MarketingSeckillActivityRepository seckillActivityRepository,
            MarketingSeckillOrderRepository seckillOrderRepository,
            MarketingGroupBuyActivityRepository groupBuyActivityRepository,
            MarketingGroupBuyTeamRepository groupBuyTeamRepository,
            MarketingGroupBuyMemberRepository groupBuyMemberRepository) {
        this.entityManager = entityManager;
        this.couponRepository = couponRepository;
        this.seckillActivityRepository = seckillActivityRepository;
        this.groupBuyTeamRepository = groupBuyTeamRepository;
        this.store = new JpaMarketingStore(
                couponRepository,
                userCouponRepository,
                seckillActivityRepository,
                seckillOrderRepository,
                groupBuyActivityRepository,
                groupBuyTeamRepository,
                groupBuyMemberRepository);
    }

    @Test
    void saveCouponUpdatesExistingRowInsteadOfPersistingDuplicateId() {
        couponRepository.saveAndFlush(couponEntity(1L, 1));
        entityManager.clear();

        assertThatCode(() -> {
                    store.saveCoupon(coupon(1L, 2));
                    entityManager.flush();
                })
                .doesNotThrowAnyException();

        entityManager.clear();
        MarketingCouponEntity saved = couponRepository.findById(1L).orElseThrow();
        assertThat(saved.getClaimedCount()).isEqualTo(2);
        assertThat(versionOf("marketing_coupon", 1L)).isEqualTo(1L);
    }

    @Test
    void saveSeckillActivityUpdatesExistingRowInsteadOfPersistingDuplicateId() {
        seckillActivityRepository.saveAndFlush(seckillActivityEntity(2L, 0));
        entityManager.clear();

        assertThatCode(() -> {
                    store.saveSeckillActivity(seckillActivity(2L, 1));
                    entityManager.flush();
                })
                .doesNotThrowAnyException();

        entityManager.clear();
        MarketingSeckillActivityEntity saved = seckillActivityRepository.findById(2L).orElseThrow();
        assertThat(saved.getSoldQuantity()).isEqualTo(1);
        assertThat(versionOf("marketing_seckill_activity", 2L)).isEqualTo(1L);
    }

    @Test
    void saveGroupBuyTeamUpdatesExistingRowInsteadOfPersistingDuplicateId() {
        groupBuyTeamRepository.saveAndFlush(groupBuyTeamEntity(3L, 1, GroupBuyStatus.OPEN));
        entityManager.clear();

        assertThatCode(() -> {
                    store.saveGroupBuyTeam(groupBuyTeam(3L, 2, GroupBuyStatus.SUCCEEDED));
                    entityManager.flush();
                })
                .doesNotThrowAnyException();

        entityManager.clear();
        MarketingGroupBuyTeamEntity saved = groupBuyTeamRepository.findById(3L).orElseThrow();
        assertThat(saved.getJoinedCount()).isEqualTo(2);
        assertThat(saved.getStatus()).isEqualTo(GroupBuyStatus.SUCCEEDED);
        assertThat(versionOf("marketing_group_buy_team", 3L)).isEqualTo(1L);
    }

    @Test
    void saveMarketingRecordsWithNewIdsStillPersists() {
        store.saveCoupon(coupon(11L, 0));
        store.saveSeckillActivity(seckillActivity(12L, 0));
        store.saveGroupBuyTeam(groupBuyTeam(13L, 1, GroupBuyStatus.OPEN));
        entityManager.flush();

        entityManager.clear();
        assertThat(couponRepository.findById(11L)).isPresent();
        assertThat(seckillActivityRepository.findById(12L)).isPresent();
        assertThat(groupBuyTeamRepository.findById(13L)).isPresent();
    }

    @Test
    void adapterUpdateKeepsOptimisticLockProtectionForStaleCouponEntity() {
        couponRepository.saveAndFlush(couponEntity(21L, 1));
        entityManager.clear();
        MarketingCouponEntity stale = couponRepository.findById(21L).orElseThrow();
        entityManager.clear();

        store.saveCoupon(coupon(21L, 2));
        entityManager.flush();
        entityManager.clear();

        stale.setClaimedCount(3);
        assertThatThrownBy(() -> couponRepository.saveAndFlush(stale))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    private static CouponDefinition coupon(Long id, int claimedCount) {
        return new CouponDefinition(
                id,
                "PLATFORM-" + id,
                "Platform",
                CouponType.THRESHOLD,
                new BigDecimal("100.00"),
                new BigDecimal("20.00"),
                BigDecimal.ZERO,
                null,
                null,
                "PLATFORM",
                100,
                claimedCount,
                NOW.minusDays(1),
                NOW.plusDays(1));
    }

    private static MarketingCouponEntity couponEntity(Long id, int claimedCount) {
        MarketingCouponEntity entity = new MarketingCouponEntity();
        entity.setId(id);
        entity.setCode("PLATFORM-" + id);
        entity.setName("Platform");
        entity.setType(CouponType.THRESHOLD);
        entity.setThresholdAmount(new BigDecimal("100.00"));
        entity.setDiscountAmount(new BigDecimal("20.00"));
        entity.setDiscountPercent(BigDecimal.ZERO);
        entity.setStackGroup("PLATFORM");
        entity.setTotalQuota(100);
        entity.setClaimedCount(claimedCount);
        entity.setStartTime(NOW.minusDays(1));
        entity.setEndTime(NOW.plusDays(1));
        return entity;
    }

    private static SeckillActivity seckillActivity(Long id, int soldQuantity) {
        return new SeckillActivity(id, 1001L, "Flash sale", 10, soldQuantity, 2, NOW.minusDays(1), NOW.plusDays(1));
    }

    private static MarketingSeckillActivityEntity seckillActivityEntity(Long id, int soldQuantity) {
        MarketingSeckillActivityEntity entity = new MarketingSeckillActivityEntity();
        entity.setId(id);
        entity.setSkuId(1001L);
        entity.setActivityName("Flash sale");
        entity.setStockQuantity(10);
        entity.setSoldQuantity(soldQuantity);
        entity.setPerUserLimit(2);
        entity.setStartTime(NOW.minusDays(1));
        entity.setEndTime(NOW.plusDays(1));
        return entity;
    }

    private static GroupBuyTeam groupBuyTeam(Long id, int joinedCount, GroupBuyStatus status) {
        return new GroupBuyTeam(id, 20L, 1001L, 7L, 2, joinedCount, status, NOW.plusHours(2));
    }

    private static MarketingGroupBuyTeamEntity groupBuyTeamEntity(
            Long id, int joinedCount, GroupBuyStatus status) {
        MarketingGroupBuyTeamEntity entity = new MarketingGroupBuyTeamEntity();
        entity.setId(id);
        entity.setActivityId(20L);
        entity.setSkuId(1001L);
        entity.setLeaderUserId(7L);
        entity.setTargetSize(2);
        entity.setJoinedCount(joinedCount);
        entity.setStatus(status);
        entity.setExpiresAt(NOW.plusHours(2));
        return entity;
    }

    private Number versionOf(String tableName, Long id) {
        return (Number) entityManager
                .getEntityManager()
                .createNativeQuery("select version from " + tableName + " where id = :id")
                .setParameter("id", id)
                .getSingleResult();
    }
}
