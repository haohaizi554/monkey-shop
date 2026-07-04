package com.example.monkey.membership.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.marketing.domain.CouponStatus;
import com.example.monkey.marketing.infrastructure.MarketingUserCouponEntity;
import com.example.monkey.marketing.infrastructure.MarketingUserCouponRepository;
import com.example.monkey.membership.domain.CouponWalletEntry;
import com.example.monkey.membership.domain.MemberCollection;
import com.example.monkey.membership.domain.MemberProfile;
import com.example.monkey.membership.domain.MembershipCheckIn;
import com.example.monkey.membership.domain.MembershipLevel;
import com.example.monkey.membership.domain.PointsLedgerEntry;
import com.example.monkey.membership.domain.PointsLedgerType;
import com.example.monkey.membership.domain.PointsWallet;
import com.example.monkey.membership.domain.PriceDropEvent;
import com.example.monkey.membership.domain.ProductSnapshot;
import com.example.monkey.product.infrastructure.Monkey;
import com.example.monkey.product.infrastructure.MonkeyRepository;
import com.example.monkey.shared.infrastructure.privacy.PiiCryptoService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

class JpaMembershipStoreTest {

    private static final Long USER_ID = 7L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 4, 10, 0);

    private final MembershipProfileRepository profileRepository = mock(MembershipProfileRepository.class);
    private final MembershipLevelHistoryRepository levelHistoryRepository =
            mock(MembershipLevelHistoryRepository.class);
    private final PointsWalletRepository walletRepository = mock(PointsWalletRepository.class);
    private final PointsLedgerRepository ledgerRepository = mock(PointsLedgerRepository.class);
    private final MembershipCheckInRepository checkInRepository = mock(MembershipCheckInRepository.class);
    private final MemberCollectionRepository collectionRepository = mock(MemberCollectionRepository.class);
    private final PriceDropEventRepository priceDropEventRepository = mock(PriceDropEventRepository.class);
    private final MarketingUserCouponRepository userCouponRepository = mock(MarketingUserCouponRepository.class);
    private final MonkeyRepository monkeyRepository = mock(MonkeyRepository.class);
    private final PiiCryptoService piiCryptoService = mock(PiiCryptoService.class);

    private final JpaMembershipStore store = new JpaMembershipStore(
            profileRepository,
            levelHistoryRepository,
            walletRepository,
            ledgerRepository,
            checkInRepository,
            collectionRepository,
            priceDropEventRepository,
            userCouponRepository,
            monkeyRepository,
            piiCryptoService);

    @BeforeEach
    void setUp() {
        when(piiCryptoService.encrypt(nullable(String.class))).thenAnswer(invocation -> prefixed("enc:", invocation));
        when(piiCryptoService.decrypt(nullable(String.class))).thenAnswer(invocation -> unprefixed("enc:", invocation));
        when(piiCryptoService.blindIndex(nullable(String.class)))
                .thenAnswer(invocation -> prefixed("hmac:", invocation));
        when(profileRepository.save(any(MembershipProfileEntity.class))).thenAnswer(firstArgument());
        when(levelHistoryRepository.save(any(MembershipLevelHistoryEntity.class)))
                .thenAnswer(firstArgument());
        when(walletRepository.save(any(PointsWalletEntity.class))).thenAnswer(firstArgument());
        when(ledgerRepository.save(any(PointsLedgerEntity.class))).thenAnswer(firstArgument());
        when(checkInRepository.save(any(MembershipCheckInEntity.class))).thenAnswer(firstArgument());
        when(collectionRepository.save(any(MemberCollectionEntity.class))).thenAnswer(firstArgument());
        when(priceDropEventRepository.save(any(PriceDropEventEntity.class))).thenAnswer(firstArgument());
    }

    @Test
    void profileWalletLedgerCheckInAndLevelHistoryRoundTripThroughJpaEntities() {
        AtomicReference<MembershipProfileEntity> profileEntity = new AtomicReference<>();
        AtomicReference<PointsWalletEntity> walletEntity = new AtomicReference<>();
        AtomicReference<PointsLedgerEntity> ledgerEntity = new AtomicReference<>();
        AtomicReference<MembershipCheckInEntity> checkInEntity = new AtomicReference<>();
        when(profileRepository.save(any(MembershipProfileEntity.class))).thenAnswer(invocation -> {
            MembershipProfileEntity entity = invocation.getArgument(0);
            profileEntity.set(entity);
            return entity;
        });
        when(walletRepository.save(any(PointsWalletEntity.class))).thenAnswer(invocation -> {
            PointsWalletEntity entity = invocation.getArgument(0);
            walletEntity.set(entity);
            return entity;
        });
        when(ledgerRepository.save(any(PointsLedgerEntity.class))).thenAnswer(invocation -> {
            PointsLedgerEntity entity = invocation.getArgument(0);
            ledgerEntity.set(entity);
            return entity;
        });
        when(checkInRepository.save(any(MembershipCheckInEntity.class))).thenAnswer(invocation -> {
            MembershipCheckInEntity entity = invocation.getArgument(0);
            checkInEntity.set(entity);
            return entity;
        });
        when(profileRepository.findByUserId(USER_ID))
                .thenAnswer(invocation -> Optional.ofNullable(profileEntity.get()));
        when(walletRepository.findByUserId(USER_ID)).thenAnswer(invocation -> Optional.ofNullable(walletEntity.get()));
        when(ledgerRepository.findByUserIdAndIdempotencyKey(USER_ID, "earn-1"))
                .thenAnswer(invocation -> Optional.ofNullable(ledgerEntity.get()));
        when(checkInRepository.findByUserIdAndCheckInDate(USER_ID, LocalDate.of(2026, 7, 4)))
                .thenAnswer(invocation -> Optional.ofNullable(checkInEntity.get()));
        when(checkInRepository.findByUserIdAndIdempotencyKey(USER_ID, "check-1"))
                .thenAnswer(invocation -> Optional.ofNullable(checkInEntity.get()));
        when(checkInRepository.findFirstByUserIdAndCheckInDateBeforeOrderByCheckInDateDesc(
                        USER_ID, LocalDate.of(2026, 7, 5)))
                .thenAnswer(invocation -> Optional.ofNullable(checkInEntity.get()));
        when(profileRepository.updateLevel(USER_ID, 0, MembershipLevel.SILVER, NOW))
                .thenReturn(1);
        when(profileRepository.updateLevel(USER_ID, 99, MembershipLevel.GOLD, NOW))
                .thenReturn(0);
        when(walletRepository.updateWallet(USER_ID, 0, 120, 120, 0, NOW)).thenReturn(1);

        MemberProfile savedProfile = store.saveProfile(new MemberProfile(
                1001L,
                USER_ID,
                MembershipLevel.BASIC,
                88,
                "Alice",
                null,
                "420001199001010010",
                null,
                NOW,
                0,
                NOW.minusDays(1),
                NOW));
        PointsWallet savedWallet = store.saveWallet(new PointsWallet(2001L, USER_ID, 120, 120, 0, 0, NOW, NOW));
        PointsLedgerEntry savedLedger = store.saveLedger(new PointsLedgerEntry(
                3001L,
                USER_ID,
                PointsLedgerType.PURCHASE,
                120,
                BigDecimal.valueOf(1.20),
                9001L,
                "order:9001",
                "earn-1",
                NOW));
        MembershipCheckIn savedCheckIn = store.saveCheckIn(
                new MembershipCheckIn(4001L, USER_ID, LocalDate.of(2026, 7, 4), 3, 14, "check-1", NOW));
        store.saveLevelHistory(5001L, USER_ID, MembershipLevel.BASIC, MembershipLevel.SILVER, "manual", USER_ID, NOW);

        assertThat(savedProfile.realName()).isEqualTo("Alice");
        assertThat(savedProfile.realNameBlindIndex()).isEqualTo("hmac:Alice");
        assertThat(savedProfile.idCardNo()).isEqualTo("420001199001010010");
        assertThat(savedWallet.balance()).isEqualTo(120);
        assertThat(savedLedger.referenceKey()).isEqualTo("order:9001");
        assertThat(savedCheckIn.streakDays()).isEqualTo(3);
        assertThat(store.findProfile(USER_ID)).contains(savedProfile);
        assertThat(store.findWallet(USER_ID)).contains(savedWallet);
        assertThat(store.findLedger(USER_ID, "earn-1")).contains(savedLedger);
        assertThat(store.findCheckIn(USER_ID, LocalDate.of(2026, 7, 4))).contains(savedCheckIn);
        assertThat(store.findCheckInByIdempotencyKey(USER_ID, "check-1")).contains(savedCheckIn);
        assertThat(store.findLatestCheckInBefore(USER_ID, LocalDate.of(2026, 7, 5)))
                .contains(savedCheckIn);
        assertThat(store.updateLevel(USER_ID, 0, MembershipLevel.SILVER, NOW)).isTrue();
        assertThat(store.updateLevel(USER_ID, 99, MembershipLevel.GOLD, NOW)).isFalse();
        assertThat(store.updateWallet(savedWallet)).isTrue();
        verify(piiCryptoService).encrypt("Alice");
        verify(piiCryptoService).blindIndex("420001199001010010");

        ArgumentCaptor<MembershipLevelHistoryEntity> history =
                ArgumentCaptor.forClass(MembershipLevelHistoryEntity.class);
        verify(levelHistoryRepository).save(history.capture());
        assertThat(ReflectionTestUtils.getField(history.getValue(), "toLevel")).isEqualTo(MembershipLevel.SILVER);
    }

    @Test
    void collectionCouponProductAndPriceDropMappingRoundTripsThroughJpaEntities() {
        AtomicReference<MemberCollectionEntity> collectionEntity = new AtomicReference<>();
        AtomicReference<PriceDropEventEntity> eventEntity = new AtomicReference<>();
        when(collectionRepository.save(any(MemberCollectionEntity.class))).thenAnswer(invocation -> {
            MemberCollectionEntity entity = invocation.getArgument(0);
            collectionEntity.set(entity);
            return entity;
        });
        when(priceDropEventRepository.save(any(PriceDropEventEntity.class))).thenAnswer(invocation -> {
            PriceDropEventEntity entity = invocation.getArgument(0);
            eventEntity.set(entity);
            return entity;
        });
        when(collectionRepository.findByUserIdAndProductId(USER_ID, 101L))
                .thenAnswer(invocation -> Optional.ofNullable(collectionEntity.get()));
        when(collectionRepository.findByUserIdOrderByCreateTimeDesc(USER_ID))
                .thenAnswer(invocation -> List.of(collectionEntity.get()));
        when(collectionRepository.findByPriceDropNotifiedFalseAndTargetPriceIsNotNullOrderByUpdateTimeAsc(
                        any(Pageable.class)))
                .thenAnswer(invocation -> List.of(collectionEntity.get()));
        when(monkeyRepository.findById(101L))
                .thenReturn(Optional.of(
                        new Monkey(101L, "Phone", "Digital", BigDecimal.valueOf(89), "screen", "/p.png", 5)));
        when(userCouponRepository.findTop20ByUserIdOrderByClaimedAtDesc(USER_ID))
                .thenReturn(List.of(couponEntity()));

        MemberCollection savedCollection = store.saveCollection(new MemberCollection(
                6001L,
                USER_ID,
                101L,
                "Phone",
                "/p.png",
                BigDecimal.valueOf(99),
                BigDecimal.valueOf(90),
                false,
                0,
                NOW.minusDays(2),
                NOW));
        PriceDropEvent savedEvent = store.savePriceDropEvent(new PriceDropEvent(
                7001L, savedCollection.id(), USER_ID, 101L, BigDecimal.valueOf(99), BigDecimal.valueOf(89), NOW));

        assertThat(store.findCollection(USER_ID, 101L)).contains(savedCollection);
        assertThat(store.findCollections(USER_ID)).containsExactly(savedCollection);
        assertThat(store.findCollectionsForPriceCheck(0)).containsExactly(savedCollection);
        assertThat(savedEvent.newPrice()).isEqualByComparingTo("89");
        assertThat(eventEntity.get().getCollectionId()).isEqualTo(savedCollection.id());
        ProductSnapshot product = store.findProduct(101L).orElseThrow();
        assertThat(product.name()).isEqualTo("Phone");
        assertThat(product.price()).isEqualByComparingTo("89");
        CouponWalletEntry coupon = store.findCouponWallet(USER_ID).getFirst();
        assertThat(coupon.status()).isEqualTo("CLAIMED");
        assertThat(coupon.couponCode()).isEqualTo("CP-1");

        store.deleteCollection(USER_ID, 101L);

        verify(collectionRepository).deleteByUserIdAndProductId(USER_ID, 101L);
        verify(collectionRepository)
                .findByPriceDropNotifiedFalseAndTargetPriceIsNotNullOrderByUpdateTimeAsc(any(Pageable.class));
    }

    private static MarketingUserCouponEntity couponEntity() {
        MarketingUserCouponEntity entity = new MarketingUserCouponEntity();
        entity.setId(8001L);
        entity.setCouponId(8101L);
        entity.setCouponCode("CP-1");
        entity.setUserId(USER_ID);
        entity.setStatus(CouponStatus.CLAIMED);
        entity.setOrderId(9001L);
        entity.setIdempotencyKey("coupon-1");
        entity.setClaimedAt(NOW.minusHours(1));
        entity.setUsedAt(NOW);
        return entity;
    }

    private static String prefixed(String prefix, org.mockito.invocation.InvocationOnMock invocation) {
        String value = invocation.getArgument(0);
        return value == null ? null : prefix + value;
    }

    private static String unprefixed(String prefix, org.mockito.invocation.InvocationOnMock invocation) {
        String value = invocation.getArgument(0);
        return value == null || !value.startsWith(prefix) ? value : value.substring(prefix.length());
    }

    private static <T> Answer<T> firstArgument() {
        return invocation -> invocation.getArgument(0);
    }
}
