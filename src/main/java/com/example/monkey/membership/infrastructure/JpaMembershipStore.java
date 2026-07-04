package com.example.monkey.membership.infrastructure;

import com.example.monkey.marketing.infrastructure.MarketingUserCouponEntity;
import com.example.monkey.marketing.infrastructure.MarketingUserCouponRepository;
import com.example.monkey.membership.domain.CouponWalletEntry;
import com.example.monkey.membership.domain.MemberCollection;
import com.example.monkey.membership.domain.MemberProfile;
import com.example.monkey.membership.domain.MembershipCheckIn;
import com.example.monkey.membership.domain.MembershipLevel;
import com.example.monkey.membership.domain.MembershipStore;
import com.example.monkey.membership.domain.PointsLedgerEntry;
import com.example.monkey.membership.domain.PointsWallet;
import com.example.monkey.membership.domain.PriceDropEvent;
import com.example.monkey.membership.domain.ProductSnapshot;
import com.example.monkey.product.infrastructure.Monkey;
import com.example.monkey.product.infrastructure.MonkeyRepository;
import com.example.monkey.shared.infrastructure.privacy.PiiCryptoService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Component
public class JpaMembershipStore implements MembershipStore {

    private final MembershipProfileRepository profileRepository;
    private final MembershipLevelHistoryRepository levelHistoryRepository;
    private final PointsWalletRepository walletRepository;
    private final PointsLedgerRepository ledgerRepository;
    private final MembershipCheckInRepository checkInRepository;
    private final MemberCollectionRepository collectionRepository;
    private final PriceDropEventRepository priceDropEventRepository;
    private final MarketingUserCouponRepository userCouponRepository;
    private final MonkeyRepository monkeyRepository;
    private final PiiCryptoService piiCryptoService;

    public JpaMembershipStore(
            MembershipProfileRepository profileRepository,
            MembershipLevelHistoryRepository levelHistoryRepository,
            PointsWalletRepository walletRepository,
            PointsLedgerRepository ledgerRepository,
            MembershipCheckInRepository checkInRepository,
            MemberCollectionRepository collectionRepository,
            PriceDropEventRepository priceDropEventRepository,
            MarketingUserCouponRepository userCouponRepository,
            MonkeyRepository monkeyRepository,
            PiiCryptoService piiCryptoService) {
        this.profileRepository = profileRepository;
        this.levelHistoryRepository = levelHistoryRepository;
        this.walletRepository = walletRepository;
        this.ledgerRepository = ledgerRepository;
        this.checkInRepository = checkInRepository;
        this.collectionRepository = collectionRepository;
        this.priceDropEventRepository = priceDropEventRepository;
        this.userCouponRepository = userCouponRepository;
        this.monkeyRepository = monkeyRepository;
        this.piiCryptoService = piiCryptoService;
    }

    @Override
    public Optional<MemberProfile> findProfile(Long userId) {
        return profileRepository.findByUserId(userId).map(this::toProfile);
    }

    @Override
    public MemberProfile saveProfile(MemberProfile profile) {
        return toProfile(profileRepository.save(toEntity(profile)));
    }

    @Override
    public boolean updateLevel(Long userId, long expectedVersion, MembershipLevel nextLevel, LocalDateTime now) {
        return profileRepository.updateLevel(userId, expectedVersion, nextLevel, now) == 1;
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
        MembershipLevelHistoryEntity entity = new MembershipLevelHistoryEntity();
        entity.setId(id);
        entity.setUserId(userId);
        entity.setFromLevel(fromLevel);
        entity.setToLevel(toLevel);
        entity.setReason(reason);
        entity.setOperatorUserId(operatorUserId);
        entity.setCreatedAt(createdAt);
        levelHistoryRepository.save(entity);
    }

    @Override
    public Optional<PointsWallet> findWallet(Long userId) {
        return walletRepository.findByUserId(userId).map(JpaMembershipStore::toWallet);
    }

    @Override
    public PointsWallet saveWallet(PointsWallet wallet) {
        return toWallet(walletRepository.save(toEntity(wallet)));
    }

    @Override
    public boolean updateWallet(PointsWallet wallet) {
        return walletRepository.updateWallet(
                        wallet.userId(),
                        wallet.version(),
                        wallet.balance(),
                        wallet.totalEarned(),
                        wallet.totalSpent(),
                        wallet.updateTime())
                == 1;
    }

    @Override
    public Optional<PointsLedgerEntry> findLedger(Long userId, String idempotencyKey) {
        return ledgerRepository
                .findByUserIdAndIdempotencyKey(userId, idempotencyKey)
                .map(JpaMembershipStore::toLedger);
    }

    @Override
    public PointsLedgerEntry saveLedger(PointsLedgerEntry entry) {
        return toLedger(ledgerRepository.save(toEntity(entry)));
    }

    @Override
    public Optional<MembershipCheckIn> findCheckIn(Long userId, LocalDate date) {
        return checkInRepository.findByUserIdAndCheckInDate(userId, date).map(JpaMembershipStore::toCheckIn);
    }

    @Override
    public Optional<MembershipCheckIn> findCheckInByIdempotencyKey(Long userId, String idempotencyKey) {
        return checkInRepository
                .findByUserIdAndIdempotencyKey(userId, idempotencyKey)
                .map(JpaMembershipStore::toCheckIn);
    }

    @Override
    public Optional<MembershipCheckIn> findLatestCheckInBefore(Long userId, LocalDate date) {
        return checkInRepository
                .findFirstByUserIdAndCheckInDateBeforeOrderByCheckInDateDesc(userId, date)
                .map(JpaMembershipStore::toCheckIn);
    }

    @Override
    public MembershipCheckIn saveCheckIn(MembershipCheckIn checkIn) {
        return toCheckIn(checkInRepository.save(toEntity(checkIn)));
    }

    @Override
    public Optional<ProductSnapshot> findProduct(Long productId) {
        return monkeyRepository.findById(productId).map(JpaMembershipStore::toProduct);
    }

    @Override
    public Optional<MemberCollection> findCollection(Long userId, Long productId) {
        return collectionRepository.findByUserIdAndProductId(userId, productId).map(JpaMembershipStore::toCollection);
    }

    @Override
    public MemberCollection saveCollection(MemberCollection collection) {
        return toCollection(collectionRepository.save(toEntity(collection)));
    }

    @Override
    public List<MemberCollection> findCollections(Long userId) {
        return collectionRepository.findByUserIdOrderByCreateTimeDesc(userId).stream()
                .map(JpaMembershipStore::toCollection)
                .toList();
    }

    @Override
    @Transactional
    public void deleteCollection(Long userId, Long productId) {
        collectionRepository.deleteByUserIdAndProductId(userId, productId);
    }

    @Override
    public List<MemberCollection> findCollectionsForPriceCheck(int limit) {
        return collectionRepository
                .findByPriceDropNotifiedFalseAndTargetPriceIsNotNullOrderByUpdateTimeAsc(
                        PageRequest.of(0, Math.max(1, limit)))
                .stream()
                .map(JpaMembershipStore::toCollection)
                .toList();
    }

    @Override
    public PriceDropEvent savePriceDropEvent(PriceDropEvent event) {
        return toPriceDropEvent(priceDropEventRepository.save(toEntity(event)));
    }

    @Override
    public List<CouponWalletEntry> findCouponWallet(Long userId) {
        return userCouponRepository.findTop20ByUserIdOrderByClaimedAtDesc(userId).stream()
                .map(JpaMembershipStore::toCoupon)
                .toList();
    }

    private MemberProfile toProfile(MembershipProfileEntity entity) {
        return new MemberProfile(
                entity.getId(),
                entity.getUserId(),
                entity.getLevel(),
                entity.getGrowthValue(),
                piiCryptoService.decrypt(entity.getRealNameEncrypted()),
                entity.getRealNameHmac(),
                piiCryptoService.decrypt(entity.getIdCardEncrypted()),
                entity.getIdCardHmac(),
                entity.getVerifiedAt(),
                entity.getVersion(),
                entity.getCreateTime(),
                entity.getUpdateTime());
    }

    private MembershipProfileEntity toEntity(MemberProfile profile) {
        MembershipProfileEntity entity =
                profileRepository.findByUserId(profile.userId()).orElseGet(MembershipProfileEntity::new);
        entity.setId(profile.id());
        entity.setUserId(profile.userId());
        entity.setLevel(profile.level());
        entity.setGrowthValue(profile.growthValue());
        entity.setRealNameEncrypted(piiCryptoService.encrypt(profile.realName()));
        entity.setRealNameHmac(blindIndex(profile.realName(), profile.realNameBlindIndex()));
        entity.setIdCardEncrypted(piiCryptoService.encrypt(profile.idCardNo()));
        entity.setIdCardHmac(blindIndex(profile.idCardNo(), profile.idCardBlindIndex()));
        entity.setVerifiedAt(profile.verifiedAt());
        entity.setVersion(profile.version());
        entity.setCreateTime(profile.createTime());
        entity.setUpdateTime(profile.updateTime());
        return entity;
    }

    private String blindIndex(String value, String existing) {
        if (StringUtils.hasText(value)) {
            return piiCryptoService.blindIndex(value);
        }
        return existing;
    }

    private static PointsWallet toWallet(PointsWalletEntity entity) {
        return new PointsWallet(
                entity.getId(),
                entity.getUserId(),
                entity.getBalance(),
                entity.getTotalEarned(),
                entity.getTotalSpent(),
                entity.getVersion(),
                entity.getCreateTime(),
                entity.getUpdateTime());
    }

    private static PointsWalletEntity toEntity(PointsWallet wallet) {
        PointsWalletEntity entity = new PointsWalletEntity();
        entity.setId(wallet.id());
        entity.setUserId(wallet.userId());
        entity.setBalance(wallet.balance());
        entity.setTotalEarned(wallet.totalEarned());
        entity.setTotalSpent(wallet.totalSpent());
        entity.setVersion(wallet.version());
        entity.setCreateTime(wallet.createTime());
        entity.setUpdateTime(wallet.updateTime());
        return entity;
    }

    private static PointsLedgerEntry toLedger(PointsLedgerEntity entity) {
        return new PointsLedgerEntry(
                entity.getId(),
                entity.getUserId(),
                entity.getType(),
                entity.getPoints(),
                entity.getMoneyEquivalent(),
                entity.getOrderId(),
                entity.getReferenceKey(),
                entity.getIdempotencyKey(),
                entity.getCreatedAt());
    }

    private static PointsLedgerEntity toEntity(PointsLedgerEntry entry) {
        PointsLedgerEntity entity = new PointsLedgerEntity();
        entity.setId(entry.id());
        entity.setUserId(entry.userId());
        entity.setType(entry.type());
        entity.setPoints(entry.points());
        entity.setMoneyEquivalent(entry.moneyEquivalent());
        entity.setOrderId(entry.orderId());
        entity.setReferenceKey(entry.referenceKey());
        entity.setIdempotencyKey(entry.idempotencyKey());
        entity.setCreatedAt(entry.createdAt());
        return entity;
    }

    private static MembershipCheckIn toCheckIn(MembershipCheckInEntity entity) {
        return new MembershipCheckIn(
                entity.getId(),
                entity.getUserId(),
                entity.getCheckInDate(),
                entity.getStreakDays(),
                entity.getRewardPoints(),
                entity.getIdempotencyKey(),
                entity.getCreatedAt());
    }

    private static MembershipCheckInEntity toEntity(MembershipCheckIn checkIn) {
        MembershipCheckInEntity entity = new MembershipCheckInEntity();
        entity.setId(checkIn.id());
        entity.setUserId(checkIn.userId());
        entity.setCheckInDate(checkIn.checkInDate());
        entity.setStreakDays(checkIn.streakDays());
        entity.setRewardPoints(checkIn.rewardPoints());
        entity.setIdempotencyKey(checkIn.idempotencyKey());
        entity.setCreatedAt(checkIn.createdAt());
        return entity;
    }

    private static MemberCollection toCollection(MemberCollectionEntity entity) {
        return new MemberCollection(
                entity.getId(),
                entity.getUserId(),
                entity.getProductId(),
                entity.getProductName(),
                entity.getProductImage(),
                entity.getLastPrice(),
                entity.getTargetPrice(),
                entity.isPriceDropNotified(),
                entity.getVersion(),
                entity.getCreateTime(),
                entity.getUpdateTime());
    }

    private static MemberCollectionEntity toEntity(MemberCollection collection) {
        MemberCollectionEntity entity = new MemberCollectionEntity();
        entity.setId(collection.id());
        entity.setUserId(collection.userId());
        entity.setProductId(collection.productId());
        entity.setProductName(collection.productName());
        entity.setProductImage(collection.productImage());
        entity.setLastPrice(collection.lastPrice());
        entity.setTargetPrice(collection.targetPrice());
        entity.setPriceDropNotified(collection.priceDropNotified());
        entity.setVersion(collection.version());
        entity.setCreateTime(collection.createTime());
        entity.setUpdateTime(collection.updateTime());
        return entity;
    }

    private static PriceDropEvent toPriceDropEvent(PriceDropEventEntity entity) {
        return new PriceDropEvent(
                entity.getId(),
                entity.getCollectionId(),
                entity.getUserId(),
                entity.getProductId(),
                entity.getOldPrice(),
                entity.getNewPrice(),
                entity.getNotifiedAt());
    }

    private static PriceDropEventEntity toEntity(PriceDropEvent event) {
        PriceDropEventEntity entity = new PriceDropEventEntity();
        entity.setId(event.id());
        entity.setCollectionId(event.collectionId());
        entity.setUserId(event.userId());
        entity.setProductId(event.productId());
        entity.setOldPrice(event.oldPrice());
        entity.setNewPrice(event.newPrice());
        entity.setNotifiedAt(event.notifiedAt());
        return entity;
    }

    private static ProductSnapshot toProduct(Monkey product) {
        return new ProductSnapshot(product.getId(), product.getName(), product.getImageUrl(), product.getPrice());
    }

    private static CouponWalletEntry toCoupon(MarketingUserCouponEntity entity) {
        return new CouponWalletEntry(
                entity.getId(),
                entity.getCouponId(),
                entity.getCouponCode(),
                entity.getUserId(),
                entity.getStatus().name(),
                entity.getOrderId(),
                entity.getClaimedAt(),
                entity.getUsedAt());
    }
}
