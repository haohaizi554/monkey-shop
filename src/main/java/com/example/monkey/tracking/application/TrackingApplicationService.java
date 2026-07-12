package com.example.monkey.tracking.application;

import com.example.monkey.order.application.observability.BusinessMetricsService;
import com.example.monkey.shared.application.observability.AuditService;
import com.example.monkey.shared.application.observability.TraceIds;
import com.example.monkey.shared.application.observability.VisitMetricsService;
import com.example.monkey.shared.application.security.AuthenticatedPrincipals;
import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.domain.id.IdGenerator;
import com.example.monkey.tracking.application.dto.FunnelStepDto;
import com.example.monkey.tracking.application.dto.ProductProfileDto;
import com.example.monkey.tracking.application.dto.RealtimeDashboardDto;
import com.example.monkey.tracking.application.dto.TrackingEventRequestDto;
import com.example.monkey.tracking.application.dto.TrackingEventResponseDto;
import com.example.monkey.tracking.application.dto.UserProfileTagDto;
import com.example.monkey.tracking.domain.FunnelStep;
import com.example.monkey.tracking.domain.ProductProfile;
import com.example.monkey.tracking.domain.RealtimeDashboard;
import com.example.monkey.tracking.domain.TrackingEvent;
import com.example.monkey.tracking.domain.TrackingEventType;
import com.example.monkey.tracking.domain.TrackingStore;
import com.example.monkey.tracking.domain.UserProfileTag;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class TrackingApplicationService {

    private static final int DASHBOARD_REFRESH_SECONDS = 5;
    private static final int DASHBOARD_WINDOW_MINUTES = 5;
    private static final int MAX_TAGS = 12;
    private static final List<TrackingEventType> FUNNEL = List.of(
            TrackingEventType.SEARCH,
            TrackingEventType.PRODUCT_VIEW,
            TrackingEventType.ADD_TO_CART,
            TrackingEventType.ORDER_CREATED,
            TrackingEventType.PAYMENT_SUCCESS);

    private final TrackingStore trackingStore;
    private final VisitMetricsService visitMetricsService;
    private final BusinessMetricsService businessMetricsService;
    private final IdGenerator idGenerator;
    private final AuditService auditService;

    public TrackingApplicationService(
            TrackingStore trackingStore,
            VisitMetricsService visitMetricsService,
            BusinessMetricsService businessMetricsService,
            IdGenerator idGenerator,
            AuditService auditService) {
        this.trackingStore = trackingStore;
        this.visitMetricsService = visitMetricsService;
        this.businessMetricsService = businessMetricsService;
        this.idGenerator = idGenerator;
        this.auditService = auditService;
    }

    @WithSpan("tracking.event-record")
    @Transactional
    public TrackingEventResponseDto recordEvent(
            SessionUser currentUser, TrackingEventRequestDto request, String clientIp) {
        Long userId = currentUser == null ? null : currentUser.id();
        String traceId = StringUtils.hasText(request.traceId()) ? request.traceId() : TraceIds.currentOrCreate();
        TrackingEvent event = new TrackingEvent(
                idGenerator.nextId(),
                userId,
                request.sessionId(),
                traceId,
                request.eventType(),
                request.page(),
                request.source(),
                request.productId(),
                request.categoryId(),
                request.orderId(),
                request.amount(),
                request.attributes(),
                request.occurredAt());
        TrackingEvent saved = trackingStore.saveEvent(event);
        businessMetricsService.recordTrackingEvent(saved.eventType().name());
        if (saved.eventType() == TrackingEventType.PAGE_VIEW) {
            visitMetricsService.recordClientPageView(saved.page(), clientIp);
        }
        if (saved.eventType() != TrackingEventType.UI_ERROR) {
            refreshProfiles(saved);
        }
        auditService.record(
                AuditService.TRACKING_EVENT_RECORDED,
                AuditService.OUTCOME_SUCCESS,
                userId,
                currentUser == null ? null : currentUser.role(),
                "tracking-event:" + saved.id(),
                null,
                "type=" + saved.eventType() + ",traceId=" + saved.traceId());
        return TrackingDtoAssembler.toEventResponse(saved);
    }

    @WithSpan("tracking.dashboard")
    @Transactional(readOnly = true)
    public RealtimeDashboardDto dashboard(Integer minutes) {
        LocalDateTime since = LocalDateTime.now().minusMinutes(normalizedWindow(minutes));
        RealtimeDashboard dashboard = new RealtimeDashboard(
                trackingStore.countEvents(TrackingEventType.PAGE_VIEW, since),
                trackingStore.countDistinctVisitors(since),
                trackingStore.countEvents(TrackingEventType.ORDER_CREATED, since),
                trackingStore.sumPaymentAmount(since),
                funnel(since),
                LocalDateTime.now(),
                DASHBOARD_REFRESH_SECONDS);
        return TrackingDtoAssembler.toDashboard(dashboard);
    }

    @WithSpan("tracking.funnel")
    @Transactional(readOnly = true)
    public List<FunnelStepDto> funnel(Integer minutes) {
        return funnel(LocalDateTime.now().minusMinutes(normalizedWindow(minutes))).stream()
                .map(TrackingDtoAssembler::toFunnelStep)
                .toList();
    }

    @WithSpan("tracking.profile.me")
    @Transactional(readOnly = true)
    public UserProfileTagDto currentUserProfile(SessionUser currentUser) {
        Long userId = AuthenticatedPrincipals.requireUserId(currentUser);
        return trackingStore
                .findUserProfile(userId)
                .map(TrackingDtoAssembler::toUserProfile)
                .orElseGet(() -> TrackingDtoAssembler.toUserProfile(emptyProfile(userId)));
    }

    @WithSpan("tracking.profile.admin")
    @Transactional(readOnly = true)
    public UserProfileTagDto userProfile(Long userId) {
        return trackingStore
                .findUserProfile(userId)
                .map(TrackingDtoAssembler::toUserProfile)
                .orElseGet(() -> TrackingDtoAssembler.toUserProfile(emptyProfile(userId)));
    }

    @WithSpan("tracking.product-profile")
    @Transactional(readOnly = true)
    public ProductProfileDto productProfile(Long productId) {
        return trackingStore
                .findProductProfile(productId)
                .map(TrackingDtoAssembler::toProductProfile)
                .orElseGet(() -> TrackingDtoAssembler.toProductProfile(
                        new ProductProfile(productId, null, List.of(), 0, BigDecimal.ZERO, LocalDateTime.now(), 0L)));
    }

    private void refreshProfiles(TrackingEvent event) {
        if (event.userId() != null) {
            UserProfileTag existing =
                    trackingStore.findUserProfile(event.userId()).orElseGet(() -> emptyProfile(event.userId()));
            UserProfileTag updated = new UserProfileTag(
                    event.userId(),
                    profileSummary(event, existing),
                    merge(existing.behaviorTags(), behaviorTags(event)),
                    merge(existing.interestTags(), interestTags(event)),
                    event.occurredAt(),
                    existing.version());
            trackingStore.saveUserProfile(updated);
            auditService.record(
                    AuditService.USER_PROFILE_TAG_UPDATED,
                    AuditService.OUTCOME_SUCCESS,
                    event.userId(),
                    null,
                    "user-profile:" + event.userId(),
                    null,
                    "behaviorTags=" + updated.behaviorTags().size() + ",interestTags="
                            + updated.interestTags().size());
        }
        if (event.productId() != null) {
            ProductProfile existing = trackingStore
                    .findProductProfile(event.productId())
                    .orElseGet(() -> new ProductProfile(
                            event.productId(),
                            event.categoryId(),
                            List.of(),
                            0,
                            BigDecimal.ZERO,
                            event.occurredAt(),
                            0L));
            long salesCount = existing.salesCount()
                    + (event.eventType() == TrackingEventType.ORDER_CREATED
                                    || event.eventType() == TrackingEventType.PAYMENT_SUCCESS
                            ? 1
                            : 0);
            ProductProfile updated = new ProductProfile(
                    event.productId(),
                    event.categoryId() == null ? existing.categoryId() : event.categoryId(),
                    merge(existing.tagVector(), interestTags(event)),
                    salesCount,
                    existing.reviewScore(),
                    event.occurredAt(),
                    existing.version());
            trackingStore.saveProductProfile(updated);
            auditService.record(
                    AuditService.PRODUCT_PROFILE_UPDATED,
                    AuditService.OUTCOME_SUCCESS,
                    event.userId(),
                    null,
                    "product-profile:" + event.productId(),
                    null,
                    "tagCount=" + updated.tagVector().size() + ",sales=" + updated.salesCount());
        }
    }

    private List<FunnelStep> funnel(LocalDateTime since) {
        List<FunnelStep> steps = new ArrayList<>();
        long baseline = Math.max(1L, trackingStore.countEvents(FUNNEL.getFirst(), since));
        for (TrackingEventType step : FUNNEL) {
            long count = trackingStore.countEvents(step, since);
            businessMetricsService.recordFunnelSnapshot(step.name(), count);
            steps.add(new FunnelStep(step, count, ratio(count, baseline)));
        }
        return steps;
    }

    private static int normalizedWindow(Integer minutes) {
        if (minutes == null) {
            return DASHBOARD_WINDOW_MINUTES;
        }
        return Math.max(1, Math.min(60, minutes));
    }

    private static UserProfileTag emptyProfile(Long userId) {
        return new UserProfileTag(userId, "", List.of(), List.of(), LocalDateTime.now(), 0L);
    }

    private static String profileSummary(TrackingEvent event, UserProfileTag existing) {
        return "last=" + event.eventType() + ",page=" + event.page() + ",previous=" + existing.profileSummary();
    }

    private static List<String> behaviorTags(TrackingEvent event) {
        return List.of("event:" + tag(event.eventType().name()), "page:" + tag(event.page()));
    }

    private static List<String> interestTags(TrackingEvent event) {
        List<String> tags = new ArrayList<>();
        if (event.productId() != null) {
            tags.add("product:" + event.productId());
        }
        if (event.categoryId() != null) {
            tags.add("category:" + event.categoryId());
        }
        if (StringUtils.hasText(event.source())) {
            tags.add("source:" + tag(event.source()));
        }
        Optional.ofNullable(event.attributes().get("keyword"))
                .filter(StringUtils::hasText)
                .map(TrackingApplicationService::tag)
                .map(keyword -> "keyword:" + keyword)
                .ifPresent(tags::add);
        return tags;
    }

    private static List<String> merge(List<String> existing, List<String> incoming) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        incoming.stream().filter(StringUtils::hasText).forEach(values::add);
        existing.stream().filter(StringUtils::hasText).forEach(values::add);
        return values.stream().limit(MAX_TAGS).toList();
    }

    private static String tag(String value) {
        return value == null ? "unknown" : value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9:_-]", "-");
    }

    private static BigDecimal ratio(long count, long baseline) {
        return BigDecimal.valueOf(count).divide(BigDecimal.valueOf(Math.max(1L, baseline)), 4, RoundingMode.HALF_UP);
    }
}
