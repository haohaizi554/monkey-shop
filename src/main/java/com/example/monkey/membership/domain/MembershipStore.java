package com.example.monkey.membership.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface MembershipStore {

    Optional<MemberProfile> findProfile(Long userId);

    MemberProfile saveProfile(MemberProfile profile);

    boolean updateLevel(Long userId, long expectedVersion, MembershipLevel nextLevel, LocalDateTime now);

    void saveLevelHistory(
            Long id,
            Long userId,
            MembershipLevel fromLevel,
            MembershipLevel toLevel,
            String reason,
            Long operatorUserId,
            LocalDateTime createdAt);

    Optional<PointsWallet> findWallet(Long userId);

    PointsWallet saveWallet(PointsWallet wallet);

    boolean updateWallet(PointsWallet wallet);

    Optional<PointsLedgerEntry> findLedger(Long userId, String idempotencyKey);

    PointsLedgerEntry saveLedger(PointsLedgerEntry entry);

    Optional<MembershipCheckIn> findCheckIn(Long userId, LocalDate date);

    Optional<MembershipCheckIn> findCheckInByIdempotencyKey(Long userId, String idempotencyKey);

    Optional<MembershipCheckIn> findLatestCheckInBefore(Long userId, LocalDate date);

    MembershipCheckIn saveCheckIn(MembershipCheckIn checkIn);

    Optional<ProductSnapshot> findProduct(Long productId);

    Optional<MemberCollection> findCollection(Long userId, Long productId);

    MemberCollection saveCollection(MemberCollection collection);

    List<MemberCollection> findCollections(Long userId);

    void deleteCollection(Long userId, Long productId);

    List<MemberCollection> findCollectionsForPriceCheck(int limit);

    PriceDropEvent savePriceDropEvent(PriceDropEvent event);

    List<CouponWalletEntry> findCouponWallet(Long userId);
}
