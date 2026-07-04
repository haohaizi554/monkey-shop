package com.example.monkey.membership.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.example.monkey.membership.application.dto.CollectionRequestDto;
import com.example.monkey.membership.application.dto.LevelChangeRequestDto;
import com.example.monkey.membership.application.dto.PointsEarnRequestDto;
import com.example.monkey.membership.application.dto.PointsRedeemRequestDto;
import com.example.monkey.membership.domain.BrowseHistoryItem;
import com.example.monkey.membership.domain.CouponWalletEntry;
import com.example.monkey.membership.domain.MemberCollection;
import com.example.monkey.membership.domain.MemberProfile;
import com.example.monkey.membership.domain.MembershipActivityStore;
import com.example.monkey.membership.domain.MembershipCheckIn;
import com.example.monkey.membership.domain.MembershipLevel;
import com.example.monkey.membership.domain.MembershipLevelTransitionResolver;
import com.example.monkey.membership.domain.MembershipStore;
import com.example.monkey.membership.domain.PointsLedgerEntry;
import com.example.monkey.membership.domain.PointsWallet;
import com.example.monkey.membership.domain.PriceDropEvent;
import com.example.monkey.membership.domain.ProductSnapshot;
import com.example.monkey.shared.application.observability.AuditService;
import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.id.IdGenerator;
import com.example.monkey.user.domain.UserAccountStore;
import com.example.monkey.user.domain.UserAccountStore.UserAccount;
import com.example.monkey.user.domain.UserMfaVerifier;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MembershipApplicationServiceTest {

    private static final SessionUser USER = new SessionUser(7L, "USER");
    private static final String VALID_TOTP_CODE = "123" + "456";
    private final FakeMembershipStore store = new FakeMembershipStore();
    private final FakeActivityStore activityStore = new FakeActivityStore();
    private final FakeIdGenerator idGenerator = new FakeIdGenerator();
    private final FakeMfaVerifier mfaVerifier = new FakeMfaVerifier();
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-04T02:00:00Z"), ZoneId.of("Asia/Shanghai"));
    private final MembershipApplicationService service = new MembershipApplicationService(
            store,
            activityStore,
            new AdjacentTransitionResolver(),
            new FakeUserAccountStore(),
            mfaVerifier,
            idGenerator,
            mock(AuditService.class),
            clock,
            Duration.ofDays(7));

    @Test
    void checkInIsIdempotentAndPostsLedger() {
        var first = service.checkIn(USER, "check-1");
        var replay = service.checkIn(USER, "check-1");

        assertThat(first.rewardPoints()).isEqualTo(10);
        assertThat(replay.rewardPoints()).isEqualTo(first.rewardPoints());
        assertThat(store.wallets.get(7L).balance()).isEqualTo(10);
        assertThat(store.ledgers).hasSize(1);
        assertThat(store.checkIns).hasSize(1);
    }

    @Test
    void pointsEarnAndRedeemUpdateWalletAndLedger() {
        service.earnPoints(USER, new PointsEarnRequestDto(99L, BigDecimal.valueOf(120), "order:99"), "earn-1");
        service.redeemPoints(USER, new PointsRedeemRequestDto(50, "redeem"), "redeem-1");

        assertThat(store.wallets.get(7L).balance()).isEqualTo(70);
        assertThat(store.wallets.get(7L).totalEarned()).isEqualTo(120);
        assertThat(store.wallets.get(7L).totalSpent()).isEqualTo(50);
        assertThat(store.ledgers).hasSize(2);
        assertThat(service.earnPoints(
                                USER, new PointsEarnRequestDto(99L, BigDecimal.valueOf(120), "order:99"), "earn-1")
                        .points())
                .isEqualTo(120);
    }

    @Test
    void levelChangeRequiresTotpAndCas() {
        assertThatThrownBy(() -> service.changeLevel(
                        USER, new LevelChangeRequestDto(MembershipLevel.SILVER, "manual", "000000")))
                .isInstanceOf(BusinessException.class);

        mfaVerifier.accept = true;
        var dashboard =
                service.changeLevel(USER, new LevelChangeRequestDto(MembershipLevel.SILVER, "manual", VALID_TOTP_CODE));

        assertThat(dashboard.profile().level()).isEqualTo(MembershipLevel.SILVER);
        assertThat(store.levelHistory).hasSize(1);
    }

    @Test
    void collectionsBrowsingAndPriceDropReminderUseProductSnapshot() {
        service.addCollection(USER, new CollectionRequestDto(101L, BigDecimal.valueOf(99)));
        service.recordBrowse(USER, new com.example.monkey.membership.application.dto.BrowseRecordRequestDto(101L));
        store.products.put(101L, new ProductSnapshot(101L, "Phone", "/p.png", BigDecimal.valueOf(89)));

        var scan = service.scanPriceDrops();

        assertThat(activityStore.items).hasSize(1);
        assertThat(scan.reminders()).isEqualTo(1);
        assertThat(store.priceDrops).hasSize(1);
        assertThat(store.collections.get("7:101").priceDropNotified()).isTrue();
    }

    private static final class AdjacentTransitionResolver implements MembershipLevelTransitionResolver {
        @Override
        public void assertAllowed(MembershipLevel currentLevel, MembershipLevel nextLevel) {
            if (Math.abs(currentLevel.ordinal() - nextLevel.ordinal()) > 1) {
                throw new BusinessException(
                        com.example.monkey.shared.domain.exception.ErrorCode.CONFLICT, "not allowed");
            }
        }
    }

    private static final class FakeIdGenerator implements IdGenerator {
        private long next = 1000;

        @Override
        public long nextId() {
            return next++;
        }
    }

    private static final class FakeMfaVerifier implements UserMfaVerifier {
        private boolean accept;

        @Override
        public boolean verifyCode(String base32Secret, String code) {
            return accept && "SECRET".equals(base32Secret) && VALID_TOTP_CODE.equals(code);
        }

        @Override
        public boolean isValidSecret(String base32Secret) {
            return "SECRET".equals(base32Secret);
        }
    }

    private static final class FakeUserAccountStore implements UserAccountStore {
        @Override
        public Optional<UserAccount> findByUsername(String username) {
            return Optional.empty();
        }

        @Override
        public Optional<UserAccount> findById(Long id) {
            return Optional.of(new UserAccount(
                    id,
                    "alice",
                    "hash",
                    "18800000000",
                    "a@example.com",
                    null,
                    "USER",
                    "Alice",
                    null,
                    false,
                    "SECRET",
                    true,
                    List.of("MEMBERSHIP_WRITE")));
        }

        @Override
        public List<UserAccount> findByRole(String role) {
            return List.of();
        }

        @Override
        public UserAccount save(UserAccount account) {
            return account;
        }

        @Override
        public List<String> findRecentPasswordHashes(Long userId) {
            return List.of();
        }

        @Override
        public void recordPasswordHistory(Long userId, String passwordHash, LocalDateTime changedAt) {}
    }

    private static final class FakeActivityStore implements MembershipActivityStore {
        private final List<BrowseHistoryItem> items = new ArrayList<>();

        @Override
        public BrowseHistoryItem record(BrowseHistoryItem item, Duration ttl) {
            items.removeIf(existing -> existing.userId().equals(item.userId())
                    && existing.productId().equals(item.productId()));
            items.add(item);
            return item;
        }

        @Override
        public List<BrowseHistoryItem> findRecent(Long userId, int limit) {
            return items.stream()
                    .filter(item -> item.userId().equals(userId))
                    .sorted(Comparator.comparing(BrowseHistoryItem::viewedAt).reversed())
                    .limit(limit)
                    .toList();
        }
    }

    private static final class FakeMembershipStore implements MembershipStore {
        private final Map<Long, MemberProfile> profiles = new HashMap<>();
        private final Map<Long, PointsWallet> wallets = new HashMap<>();
        private final Map<String, PointsLedgerEntry> ledgers = new HashMap<>();
        private final Map<String, MembershipCheckIn> checkIns = new HashMap<>();
        private final Map<String, MemberCollection> collections = new HashMap<>();
        private final Map<Long, ProductSnapshot> products = new HashMap<>();
        private final List<PriceDropEvent> priceDrops = new ArrayList<>();
        private final List<String> levelHistory = new ArrayList<>();

        private FakeMembershipStore() {
            products.put(101L, new ProductSnapshot(101L, "Phone", "/p.png", BigDecimal.valueOf(199)));
        }

        @Override
        public Optional<MemberProfile> findProfile(Long userId) {
            return Optional.ofNullable(profiles.get(userId));
        }

        @Override
        public MemberProfile saveProfile(MemberProfile profile) {
            profiles.put(profile.userId(), profile);
            return profile;
        }

        @Override
        public boolean updateLevel(Long userId, long expectedVersion, MembershipLevel nextLevel, LocalDateTime now) {
            MemberProfile current = profiles.get(userId);
            if (current == null || current.version() != expectedVersion) {
                return false;
            }
            profiles.put(userId, current.withLevel(nextLevel, now));
            return true;
        }

        @Override
        public void saveLevelHistory(
                Long id,
                Long userId,
                MembershipLevel fromLevel,
                MembershipLevel toLevel,
                String reason,
                Long operatorUserId,
                LocalDateTime createdAt) {
            levelHistory.add(fromLevel + ">" + toLevel);
        }

        @Override
        public Optional<PointsWallet> findWallet(Long userId) {
            return Optional.ofNullable(wallets.get(userId));
        }

        @Override
        public PointsWallet saveWallet(PointsWallet wallet) {
            wallets.put(wallet.userId(), wallet);
            return wallet;
        }

        @Override
        public boolean updateWallet(PointsWallet wallet) {
            PointsWallet current = wallets.get(wallet.userId());
            if (current == null || current.version() != wallet.version()) {
                return false;
            }
            wallets.put(
                    wallet.userId(),
                    new PointsWallet(
                            wallet.id(),
                            wallet.userId(),
                            wallet.balance(),
                            wallet.totalEarned(),
                            wallet.totalSpent(),
                            wallet.version() + 1,
                            wallet.createTime(),
                            wallet.updateTime()));
            return true;
        }

        @Override
        public Optional<PointsLedgerEntry> findLedger(Long userId, String idempotencyKey) {
            return Optional.ofNullable(ledgers.get(userId + ":" + idempotencyKey));
        }

        @Override
        public PointsLedgerEntry saveLedger(PointsLedgerEntry entry) {
            ledgers.put(entry.userId() + ":" + entry.idempotencyKey(), entry);
            return entry;
        }

        @Override
        public Optional<MembershipCheckIn> findCheckIn(Long userId, LocalDate date) {
            return Optional.ofNullable(checkIns.get(userId + ":" + date));
        }

        @Override
        public Optional<MembershipCheckIn> findCheckInByIdempotencyKey(Long userId, String idempotencyKey) {
            return checkIns.values().stream()
                    .filter(checkIn -> checkIn.userId().equals(userId)
                            && checkIn.idempotencyKey().equals(idempotencyKey))
                    .findFirst();
        }

        @Override
        public Optional<MembershipCheckIn> findLatestCheckInBefore(Long userId, LocalDate date) {
            return checkIns.values().stream()
                    .filter(checkIn -> checkIn.userId().equals(userId)
                            && checkIn.checkInDate().isBefore(date))
                    .max(Comparator.comparing(MembershipCheckIn::checkInDate));
        }

        @Override
        public MembershipCheckIn saveCheckIn(MembershipCheckIn checkIn) {
            checkIns.put(checkIn.userId() + ":" + checkIn.checkInDate(), checkIn);
            return checkIn;
        }

        @Override
        public Optional<ProductSnapshot> findProduct(Long productId) {
            return Optional.ofNullable(products.get(productId));
        }

        @Override
        public Optional<MemberCollection> findCollection(Long userId, Long productId) {
            return Optional.ofNullable(collections.get(userId + ":" + productId));
        }

        @Override
        public MemberCollection saveCollection(MemberCollection collection) {
            collections.put(collection.userId() + ":" + collection.productId(), collection);
            return collection;
        }

        @Override
        public List<MemberCollection> findCollections(Long userId) {
            return collections.values().stream()
                    .filter(collection -> collection.userId().equals(userId))
                    .toList();
        }

        @Override
        public void deleteCollection(Long userId, Long productId) {
            collections.remove(userId + ":" + productId);
        }

        @Override
        public List<MemberCollection> findCollectionsForPriceCheck(int limit) {
            return collections.values().stream().limit(limit).toList();
        }

        @Override
        public PriceDropEvent savePriceDropEvent(PriceDropEvent event) {
            priceDrops.add(event);
            return event;
        }

        @Override
        public List<CouponWalletEntry> findCouponWallet(Long userId) {
            return List.of();
        }
    }
}
