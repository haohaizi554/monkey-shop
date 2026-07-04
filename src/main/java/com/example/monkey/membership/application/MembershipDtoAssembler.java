package com.example.monkey.membership.application;

import com.example.monkey.membership.application.dto.BrowseHistoryDto;
import com.example.monkey.membership.application.dto.CheckInResponseDto;
import com.example.monkey.membership.application.dto.CouponWalletEntryDto;
import com.example.monkey.membership.application.dto.MemberCollectionDto;
import com.example.monkey.membership.application.dto.MemberProfileDto;
import com.example.monkey.membership.application.dto.MembershipDashboardDto;
import com.example.monkey.membership.application.dto.PointsLedgerEntryDto;
import com.example.monkey.membership.application.dto.PointsWalletDto;
import com.example.monkey.membership.application.dto.PriceDropScanResponseDto;
import com.example.monkey.membership.domain.BrowseHistoryItem;
import com.example.monkey.membership.domain.CouponWalletEntry;
import com.example.monkey.membership.domain.MemberCollection;
import com.example.monkey.membership.domain.MemberProfile;
import com.example.monkey.membership.domain.MembershipCheckIn;
import com.example.monkey.membership.domain.PointsLedgerEntry;
import com.example.monkey.membership.domain.PointsWallet;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.util.StringUtils;

final class MembershipDtoAssembler {

    private static final BigDecimal POINTS_TO_CNY = BigDecimal.valueOf(100);

    private MembershipDtoAssembler() {}

    static MembershipDashboardDto toDashboard(
            MemberProfile profile,
            PointsWallet wallet,
            List<CouponWalletEntry> coupons,
            List<MemberCollection> collections,
            List<BrowseHistoryItem> browseHistory) {
        return new MembershipDashboardDto(
                toProfile(profile),
                toWallet(wallet),
                coupons.stream().map(MembershipDtoAssembler::toCoupon).toList(),
                collections.stream().map(MembershipDtoAssembler::toCollection).toList(),
                browseHistory.stream().map(MembershipDtoAssembler::toBrowse).toList());
    }

    static CheckInResponseDto toCheckIn(MembershipCheckIn checkIn, PointsWallet wallet) {
        return new CheckInResponseDto(
                checkIn.checkInDate(), checkIn.streakDays(), checkIn.rewardPoints(), toWallet(wallet));
    }

    static PriceDropScanResponseDto toPriceDropScan(int scanned, int reminders) {
        return new PriceDropScanResponseDto(scanned, reminders);
    }

    static MemberProfileDto toProfile(MemberProfile profile) {
        return new MemberProfileDto(
                profile.userId(),
                profile.level(),
                profile.growthValue(),
                profile.verified(),
                maskName(profile.realName()),
                maskIdCard(profile.idCardNo()),
                profile.version(),
                profile.level().benefits());
    }

    static PointsWalletDto toWallet(PointsWallet wallet) {
        return new PointsWalletDto(
                wallet.userId(),
                wallet.balance(),
                wallet.totalEarned(),
                wallet.totalSpent(),
                moneyEquivalent(wallet.balance()),
                wallet.version());
    }

    static PointsLedgerEntryDto toLedger(PointsLedgerEntry entry) {
        return new PointsLedgerEntryDto(
                entry.id(),
                entry.type(),
                entry.points(),
                entry.moneyEquivalent(),
                entry.orderId(),
                entry.referenceKey(),
                entry.createdAt());
    }

    static MemberCollectionDto toCollection(MemberCollection collection) {
        return new MemberCollectionDto(
                collection.id(),
                collection.productId(),
                collection.productName(),
                collection.productImage(),
                collection.lastPrice(),
                collection.targetPrice(),
                collection.priceDropNotified(),
                collection.createTime(),
                collection.updateTime());
    }

    static BrowseHistoryDto toBrowse(BrowseHistoryItem item) {
        return new BrowseHistoryDto(
                item.productId(), item.productName(), item.productImage(), item.viewedAt(), item.expiresAt());
    }

    static CouponWalletEntryDto toCoupon(CouponWalletEntry coupon) {
        return new CouponWalletEntryDto(
                coupon.id(),
                coupon.couponId(),
                coupon.couponCode(),
                coupon.status(),
                coupon.orderId(),
                coupon.claimedAt(),
                coupon.usedAt());
    }

    static BigDecimal moneyEquivalent(long points) {
        return BigDecimal.valueOf(points).divide(POINTS_TO_CNY, 2, RoundingMode.DOWN);
    }

    private static String maskName(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= 1 ? "*" : trimmed.charAt(0) + "*";
    }

    private static String maskIdCard(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() <= 8) {
            return "****";
        }
        return trimmed.substring(0, 4) + "**********" + trimmed.substring(trimmed.length() - 4);
    }
}
