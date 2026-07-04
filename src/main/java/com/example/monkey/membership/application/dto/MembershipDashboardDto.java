package com.example.monkey.membership.application.dto;

import java.util.List;

public record MembershipDashboardDto(
        MemberProfileDto profile,
        PointsWalletDto wallet,
        List<CouponWalletEntryDto> coupons,
        List<MemberCollectionDto> collections,
        List<BrowseHistoryDto> browseHistory) {}
