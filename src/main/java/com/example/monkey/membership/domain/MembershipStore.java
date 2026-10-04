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

    /** Returns the durable reward fact for a payment in the current tenant. */
    default Optional<PurchaseRewardFact> findPurchaseReward(Long paymentId) {
        return Optional.empty();
    }

    /** Persists a payment reward fact or its optimistic-lock update. */
    default PurchaseRewardFact savePurchaseReward(PurchaseRewardFact fact) {
        throw new UnsupportedOperationException("Purchase reward persistence is not configured");
    }

    /** Returns an event bound to this payment reward fact and event key. */
    default Optional<PurchaseRewardEvent> findPurchaseRewardEvent(Long paymentId, String eventKey) {
        return Optional.empty();
    }

    /** Returns any event with this tenant-scoped key, including events bound to another payment. */
    default Optional<PurchaseRewardEvent> findPurchaseRewardEventByKey(String eventKey) {
        return Optional.empty();
    }

    /** Persists immutable payment reward event evidence. */
    default PurchaseRewardEvent savePurchaseRewardEvent(PurchaseRewardEvent event) {
        throw new UnsupportedOperationException("Purchase reward event persistence is not configured");
    }

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
