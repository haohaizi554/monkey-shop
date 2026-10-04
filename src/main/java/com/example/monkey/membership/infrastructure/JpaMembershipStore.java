package com.example.monkey.membership.infrastructure;

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
import com.example.monkey.membership.domain.PurchaseRewardEvent;
import com.example.monkey.membership.domain.PurchaseRewardEventType;
import com.example.monkey.membership.domain.PurchaseRewardFact;
import com.example.monkey.shared.application.tenant.TenantContext;
import com.example.monkey.shared.application.storage.ImageCleanupService;
import com.example.monkey.shared.application.storage.ImageReferenceTransactions;
import com.example.monkey.shared.domain.storage.ImageReferenceService;
import com.example.monkey.shared.infrastructure.privacy.PiiCryptoService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Component
public class JpaMembershipStore implements MembershipStore {

    private static final String IMAGE_REFERENCE_CONFIGURATION_ERROR =
            "Image reference services are required for trackable image writes";

    private final MembershipProfileRepository profileRepository;
    private final MembershipLevelHistoryRepository levelHistoryRepository;
    private final PointsWalletRepository walletRepository;
    private final PointsLedgerRepository ledgerRepository;
    private final MembershipCheckInRepository checkInRepository;
    private final MemberCollectionRepository collectionRepository;
    private final PriceDropEventRepository priceDropEventRepository;
    private final MembershipPurchaseRewardRepository purchaseRewardRepository;
    private final MembershipPurchaseRewardEventRepository purchaseRewardEventRepository;
    private final JdbcTemplate jdbcTemplate;
    private final PiiCryptoService piiCryptoService;
    private final ImageReferenceService imageReferenceService;
    private final ImageCleanupService imageCleanupService;

    @Autowired
    public JpaMembershipStore(
            MembershipProfileRepository profileRepository,
            MembershipLevelHistoryRepository levelHistoryRepository,
            PointsWalletRepository walletRepository,
            PointsLedgerRepository ledgerRepository,
            MembershipCheckInRepository checkInRepository,
            MemberCollectionRepository collectionRepository,
            PriceDropEventRepository priceDropEventRepository,
            JdbcTemplate jdbcTemplate,
            PiiCryptoService piiCryptoService,
            ImageReferenceService imageReferenceService,
            ImageCleanupService imageCleanupService,
            MembershipPurchaseRewardRepository purchaseRewardRepository,
            MembershipPurchaseRewardEventRepository purchaseRewardEventRepository) {
        this.profileRepository = profileRepository;
        this.levelHistoryRepository = levelHistoryRepository;
        this.walletRepository = walletRepository;
        this.ledgerRepository = ledgerRepository;
        this.checkInRepository = checkInRepository;
        this.collectionRepository = collectionRepository;
        this.priceDropEventRepository = priceDropEventRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.piiCryptoService = piiCryptoService;
        this.imageReferenceService = imageReferenceService;
        this.imageCleanupService = imageCleanupService;
        this.purchaseRewardRepository = purchaseRewardRepository;
        this.purchaseRewardEventRepository = purchaseRewardEventRepository;
    }

    /** Compatibility constructor for direct persistence mapping tests. */
    public JpaMembershipStore(
            MembershipProfileRepository profileRepository,
            MembershipLevelHistoryRepository levelHistoryRepository,
            PointsWalletRepository walletRepository,
            PointsLedgerRepository ledgerRepository,
            MembershipCheckInRepository checkInRepository,
            MemberCollectionRepository collectionRepository,
            PriceDropEventRepository priceDropEventRepository,
            JdbcTemplate jdbcTemplate,
            PiiCryptoService piiCryptoService) {
        this(
                profileRepository,
                levelHistoryRepository,
                walletRepository,
                ledgerRepository,
                checkInRepository,
                collectionRepository,
                priceDropEventRepository,
                jdbcTemplate,
                piiCryptoService,
                null,
                null,
                null,
                null);
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
                        wallet.pointsDebt(),
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
    public Optional<PurchaseRewardFact> findPurchaseReward(Long paymentId) {
        if (purchaseRewardRepository == null) {
            return Optional.empty();
        }
        return purchaseRewardRepository
                .findLockedByTenantIdAndPaymentId(TenantContext.currentTenantIdOrDefault(), paymentId)
                .map(JpaMembershipStore::toPurchaseRewardFact);
    }

    @Override
    @Transactional
    public PurchaseRewardFact savePurchaseReward(PurchaseRewardFact fact) {
        requirePurchaseRewardPersistence();
        MembershipPurchaseRewardEntity entity = fact.id() == null
                ? new MembershipPurchaseRewardEntity()
                : purchaseRewardRepository.findById(fact.id()).orElseGet(MembershipPurchaseRewardEntity::new);
        apply(entity, fact);
        entity.setTenantId(TenantContext.currentTenantIdOrDefault());
        return toPurchaseRewardFact(purchaseRewardRepository.save(entity));
    }

    @Override
    public Optional<PurchaseRewardEvent> findPurchaseRewardEvent(Long paymentId, String eventKey) {
        if (purchaseRewardEventRepository == null) {
            return Optional.empty();
        }
        return purchaseRewardEventRepository
                .findLockedByTenantIdAndPaymentIdAndEventKey(
                        TenantContext.currentTenantIdOrDefault(), paymentId, eventKey)
                .map(JpaMembershipStore::toPurchaseRewardEvent);
    }

    @Override
    public Optional<PurchaseRewardEvent> findPurchaseRewardEventByKey(String eventKey) {
        if (purchaseRewardEventRepository == null) {
            return Optional.empty();
        }
        return purchaseRewardEventRepository
                .findLockedByTenantIdAndEventKey(TenantContext.currentTenantIdOrDefault(), eventKey)
                .map(JpaMembershipStore::toPurchaseRewardEvent);
    }

    @Override
    @Transactional
    public PurchaseRewardEvent savePurchaseRewardEvent(PurchaseRewardEvent event) {
        requirePurchaseRewardPersistence();
        MembershipPurchaseRewardEventEntity entity = event.id() == null
                ? new MembershipPurchaseRewardEventEntity()
                : purchaseRewardEventRepository.findById(event.id()).orElseGet(MembershipPurchaseRewardEventEntity::new);
        entity.setId(event.id());
        entity.setRewardFactId(event.rewardFactId());
        entity.setPaymentId(event.paymentId());
        entity.setOrderId(event.orderId());
        entity.setUserId(event.userId());
        entity.setType(event.type());
        entity.setEventKey(event.eventKey());
        entity.setFingerprint(event.fingerprint());
        entity.setRefundAmount(event.refundAmount());
        entity.setCumulativeRefundedAmount(event.cumulativeRefundedAmount());
        entity.setTargetReversedPoints(event.targetReversedPoints());
        entity.setAppliedPoints(event.appliedPoints());
        entity.setCreatedAt(event.createdAt());
        entity.setTenantId(TenantContext.currentTenantIdOrDefault());
        return toPurchaseRewardEvent(purchaseRewardEventRepository.save(entity));
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
        return jdbcTemplate
                .query("""
                        SELECT id, name, image_url, original_price
                        FROM product_spu
                        WHERE id = ?
                          AND tenant_id = ?
                          AND status = 'LISTED'
                          AND deleted = false
                        """, (rs, rowNum) -> toProduct(rs), productId, TenantContext.currentTenantIdOrDefault())
                .stream()
                .findFirst();
    }

    @Override
    public Optional<MemberCollection> findCollection(Long userId, Long productId) {
        return collectionRepository.findByUserIdAndProductId(userId, productId).map(JpaMembershipStore::toCollection);
    }

    @Override
    public MemberCollection saveCollection(MemberCollection collection) {
        MemberCollectionEntity existing = collectionRepository.findById(collection.id()).orElse(null);
        String oldImage = existing == null ? null : existing.getProductImage();
        requireImageTrackingConfigured(oldImage, collection.productImage());
        boolean imageChanged = !Objects.equals(oldImage, collection.productImage());
        if (imageReferenceService != null && imageChanged) {
            ImageReferenceTransactions.retainBeforeWrite(imageReferenceService, collection.productImage());
        }
        MemberCollection saved = toCollection(collectionRepository.save(toEntity(collection)));
        if (imageReferenceService != null && imageChanged && oldImage != null) {
            ImageReferenceTransactions.releaseAfterCommit(imageReferenceService, imageCleanupService, oldImage);
        }
        return saved;
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
        MemberCollectionEntity existing = collectionRepository.findByUserIdAndProductId(userId, productId).orElse(null);
        requireImageTrackingConfigured(existing == null ? null : existing.getProductImage(), null);
        collectionRepository.deleteByUserIdAndProductId(userId, productId);
        if (imageReferenceService != null && existing != null) {
            ImageReferenceTransactions.releaseAfterCommit(
                    imageReferenceService, imageCleanupService, existing.getProductImage());
        }
    }

    private void requireImageTrackingConfigured(String oldImage, String newImage) {
        if ((ImageReferenceService.isTrackable(oldImage) || ImageReferenceService.isTrackable(newImage))
                && (imageReferenceService == null || imageCleanupService == null)) {
            throw new IllegalStateException(IMAGE_REFERENCE_CONFIGURATION_ERROR);
        }
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
        return jdbcTemplate.query(
                """
                SELECT id, coupon_id, coupon_code, user_id, status, order_id, claimed_at, used_at
                FROM marketing_user_coupon
                WHERE tenant_id = ?
                  AND user_id = ?
                ORDER BY claimed_at DESC
                LIMIT ?
                """, (rs, rowNum) -> toCoupon(rs), TenantContext.currentTenantIdOrDefault(), userId, 20);
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
                entity.getIdentityStatus(),
                entity.getIdentitySubmittedAt(),
                entity.getIdentityReviewedAt(),
                entity.getIdentityReviewedBy(),
                entity.getIdentityReviewReason(),
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
        entity.setIdentityStatus(profile.identityStatus());
        entity.setIdentitySubmittedAt(profile.identitySubmittedAt());
        entity.setIdentityReviewedAt(profile.identityReviewedAt());
        entity.setIdentityReviewedBy(profile.identityReviewedBy());
        entity.setIdentityReviewReason(profile.identityReviewReason());
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
                entity.getPointsDebt(),
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
        entity.setPointsDebt(wallet.pointsDebt());
        entity.setVersion(wallet.version());
        entity.setCreateTime(wallet.createTime());
        entity.setUpdateTime(wallet.updateTime());
        return entity;
    }

    private static PurchaseRewardFact toPurchaseRewardFact(MembershipPurchaseRewardEntity entity) {
        return new PurchaseRewardFact(
                entity.getId(),
                entity.getPaymentId(),
                entity.getOrderId(),
                entity.getUserId(),
                entity.getOriginalPaidAmount(),
                entity.getProviderTradeNo(),
                entity.getPointsMultiplier(),
                entity.getAwardedPoints(),
                entity.getReversedPoints(),
                entity.getCumulativeRefundedAmount(),
                entity.getAwardEventKey(),
                entity.getAwardFingerprint(),
                entity.getVersion(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    private static void apply(MembershipPurchaseRewardEntity entity, PurchaseRewardFact fact) {
        entity.setId(fact.id());
        entity.setPaymentId(fact.paymentId());
        entity.setOrderId(fact.orderId());
        entity.setUserId(fact.userId());
        entity.setOriginalPaidAmount(fact.originalPaidAmount());
        entity.setProviderTradeNo(fact.providerTradeNo());
        entity.setPointsMultiplier(fact.pointsMultiplier());
        entity.setAwardedPoints(fact.awardedPoints());
        entity.setReversedPoints(fact.reversedPoints());
        entity.setCumulativeRefundedAmount(fact.cumulativeRefundedAmount());
        entity.setAwardEventKey(fact.awardEventKey());
        entity.setAwardFingerprint(fact.awardFingerprint());
        entity.setVersion(fact.version());
        entity.setCreatedAt(fact.createdAt());
        entity.setUpdatedAt(fact.updatedAt());
    }

    private static PurchaseRewardEvent toPurchaseRewardEvent(MembershipPurchaseRewardEventEntity entity) {
        return new PurchaseRewardEvent(
                entity.getId(),
                entity.getRewardFactId(),
                entity.getPaymentId(),
                entity.getOrderId(),
                entity.getUserId(),
                entity.getType(),
                entity.getEventKey(),
                entity.getFingerprint(),
                entity.getRefundAmount(),
                entity.getCumulativeRefundedAmount(),
                entity.getTargetReversedPoints(),
                entity.getAppliedPoints(),
                entity.getCreatedAt());
    }

    private void requirePurchaseRewardPersistence() {
        if (purchaseRewardRepository == null || purchaseRewardEventRepository == null) {
            throw new IllegalStateException("Purchase reward persistence is not configured");
        }
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
                entity.getMutationFingerprint(),
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
        entity.setMutationFingerprint(entry.mutationFingerprint());
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

    private static ProductSnapshot toProduct(ResultSet rs) throws SQLException {
        // Membership has no user identity or region context; use the catalog base price consistently.
        return new ProductSnapshot(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("image_url"),
                rs.getBigDecimal("original_price"));
    }

    private static CouponWalletEntry toCoupon(ResultSet rs) throws SQLException {
        return new CouponWalletEntry(
                rs.getLong("id"),
                rs.getLong("coupon_id"),
                rs.getString("coupon_code"),
                rs.getLong("user_id"),
                rs.getString("status"),
                nullableLong(rs, "order_id"),
                rs.getTimestamp("claimed_at").toLocalDateTime(),
                nullableDateTime(rs, "used_at"));
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static LocalDateTime nullableDateTime(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
