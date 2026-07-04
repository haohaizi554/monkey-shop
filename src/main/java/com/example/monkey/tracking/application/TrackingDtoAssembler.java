package com.example.monkey.tracking.application;

import com.example.monkey.tracking.application.dto.FunnelStepDto;
import com.example.monkey.tracking.application.dto.ProductProfileDto;
import com.example.monkey.tracking.application.dto.RealtimeDashboardDto;
import com.example.monkey.tracking.application.dto.TrackingEventResponseDto;
import com.example.monkey.tracking.application.dto.UserProfileTagDto;
import com.example.monkey.tracking.domain.FunnelStep;
import com.example.monkey.tracking.domain.ProductProfile;
import com.example.monkey.tracking.domain.RealtimeDashboard;
import com.example.monkey.tracking.domain.TrackingEvent;
import com.example.monkey.tracking.domain.UserProfileTag;

final class TrackingDtoAssembler {

    private TrackingDtoAssembler() {}

    static TrackingEventResponseDto toEventResponse(TrackingEvent event) {
        return new TrackingEventResponseDto(
                event.id(),
                event.userId(),
                event.sessionId(),
                event.traceId(),
                event.eventType(),
                event.page(),
                event.occurredAt());
    }

    static UserProfileTagDto toUserProfile(UserProfileTag profile) {
        return new UserProfileTagDto(
                profile.userId(),
                profile.profileSummary(),
                profile.behaviorTags(),
                profile.interestTags(),
                profile.lastEventAt(),
                profile.version());
    }

    static ProductProfileDto toProductProfile(ProductProfile profile) {
        return new ProductProfileDto(
                profile.productId(),
                profile.categoryId(),
                profile.tagVector(),
                profile.salesCount(),
                profile.reviewScore(),
                profile.lastEventAt(),
                profile.version());
    }

    static RealtimeDashboardDto toDashboard(RealtimeDashboard dashboard) {
        return new RealtimeDashboardDto(
                dashboard.pageViews(),
                dashboard.uniqueVisitors(),
                dashboard.orderCount(),
                dashboard.paymentAmount(),
                dashboard.funnel().stream()
                        .map(TrackingDtoAssembler::toFunnelStep)
                        .toList(),
                dashboard.generatedAt(),
                dashboard.refreshIntervalSeconds());
    }

    static FunnelStepDto toFunnelStep(FunnelStep step) {
        return new FunnelStepDto(step.eventType(), step.count(), step.conversionRate());
    }
}
