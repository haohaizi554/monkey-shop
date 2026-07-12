package com.example.monkey.tracking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.example.monkey.order.application.observability.BusinessMetricsService;
import com.example.monkey.shared.application.observability.AuditService;
import com.example.monkey.shared.application.observability.VisitMetricsService;
import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.domain.id.IdGenerator;
import com.example.monkey.tracking.application.dto.TrackingEventRequestDto;
import com.example.monkey.tracking.domain.ProductProfile;
import com.example.monkey.tracking.domain.TrackingEvent;
import com.example.monkey.tracking.domain.TrackingEventType;
import com.example.monkey.tracking.domain.TrackingStore;
import com.example.monkey.tracking.domain.UserProfileTag;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class TrackingApplicationServiceTest {

    private static final SessionUser USER = new SessionUser(7L, "USER");
    private final FakeTrackingStore trackingStore = new FakeTrackingStore();
    private final VisitMetricsService visitMetricsService = mock(VisitMetricsService.class);
    private final BusinessMetricsService businessMetricsService = mock(BusinessMetricsService.class);
    private final AuditService auditService = mock(AuditService.class);
    private final TrackingApplicationService service = new TrackingApplicationService(
            trackingStore, visitMetricsService, businessMetricsService, new IncrementingIdGenerator(), auditService);

    @Test
    void recordEventCreatesTraceableEventAndRefreshesUserAndProductProfiles() {
        LocalDateTime occurredAt = LocalDateTime.now();
        var response = service.recordEvent(
                USER,
                new TrackingEventRequestDto(
                        TrackingEventType.PRODUCT_VIEW,
                        "session-a",
                        "trace-a",
                        "/shop/42",
                        "web",
                        42L,
                        5L,
                        null,
                        null,
                        Map.of("keyword", "Phone Case"),
                        occurredAt),
                "203.0.113.7");

        assertThat(response.id()).isEqualTo(1001L);
        assertThat(response.userId()).isEqualTo(7L);
        assertThat(response.traceId()).isEqualTo("trace-a");
        assertThat(trackingStore.events).hasSize(1);
        assertThat(trackingStore.userProfiles.get(7L).profileSummary()).contains("PRODUCT_VIEW", "/shop/42");
        assertThat(trackingStore.userProfiles.get(7L).behaviorTags()).contains("event:product_view", "page:-shop-42");
        assertThat(trackingStore.userProfiles.get(7L).interestTags())
                .contains("product:42", "category:5", "source:web", "keyword:phone-case");
        assertThat(trackingStore.productProfiles.get(42L).tagVector())
                .contains("product:42", "category:5", "keyword:phone-case");
        verify(businessMetricsService).recordTrackingEvent("PRODUCT_VIEW");
        verify(auditService)
                .record(
                        eq(AuditService.TRACKING_EVENT_RECORDED),
                        eq(AuditService.OUTCOME_SUCCESS),
                        eq(7L),
                        eq("USER"),
                        eq("tracking-event:1001"),
                        isNull(),
                        contains("traceId=trace-a"));
    }

    @Test
    void uiErrorEventRemainsTraceableWithoutPollutingRecommendationProfiles() {
        var response = service.recordEvent(
                USER,
                new TrackingEventRequestDto(
                        TrackingEventType.UI_ERROR,
                        "ui-session",
                        "ui-support-reference",
                        "/shop",
                        "web-ui",
                        null,
                        null,
                        null,
                        null,
                        Map.of("errorName", "TypeError", "info", "render function"),
                        LocalDateTime.now()),
                "203.0.113.9");

        assertThat(response.traceId()).isEqualTo("ui-support-reference");
        assertThat(trackingStore.events).hasSize(1);
        assertThat(trackingStore.userProfiles).isEmpty();
        assertThat(trackingStore.productProfiles).isEmpty();
        verify(businessMetricsService).recordTrackingEvent("UI_ERROR");
    }

    @Test
    void dashboardAggregatesPvUvPaymentAndFunnelSnapshots() {
        record(null, TrackingEventType.PAGE_VIEW, "pv-session", null, null, null, null, "/shop");
        record(USER, TrackingEventType.SEARCH, "buyer-session", null, null, null, null, "/search");
        record(USER, TrackingEventType.PRODUCT_VIEW, "buyer-session", 42L, 5L, null, null, "/shop/42");
        record(USER, TrackingEventType.ADD_TO_CART, "buyer-session", 42L, 5L, null, null, "/cart");
        record(USER, TrackingEventType.ORDER_CREATED, "buyer-session", 42L, 5L, 900L, null, "/checkout");
        record(
                USER,
                TrackingEventType.PAYMENT_SUCCESS,
                "buyer-session",
                42L,
                5L,
                900L,
                new BigDecimal("199.99"),
                "/payment/900");

        var dashboard = service.dashboard(5);

        assertThat(dashboard.pageViews()).isEqualTo(1);
        assertThat(dashboard.uniqueVisitors()).isEqualTo(2);
        assertThat(dashboard.orderCount()).isEqualTo(1);
        assertThat(dashboard.paymentAmount()).isEqualByComparingTo("199.99");
        assertThat(dashboard.refreshIntervalSeconds()).isEqualTo(5);
        assertThat(dashboard.funnel()).hasSize(5);
        assertThat(dashboard.funnel())
                .extracting("eventType")
                .containsExactly(
                        TrackingEventType.SEARCH,
                        TrackingEventType.PRODUCT_VIEW,
                        TrackingEventType.ADD_TO_CART,
                        TrackingEventType.ORDER_CREATED,
                        TrackingEventType.PAYMENT_SUCCESS);
        verify(visitMetricsService).recordClientPageView("/shop", "203.0.113.8");
        verify(businessMetricsService).recordFunnelSnapshot("SEARCH", 1L);
        verify(businessMetricsService).recordFunnelSnapshot("PAYMENT_SUCCESS", 1L);
    }

    private void record(
            SessionUser user,
            TrackingEventType eventType,
            String sessionId,
            Long productId,
            Long categoryId,
            Long orderId,
            BigDecimal amount,
            String page) {
        service.recordEvent(
                user,
                new TrackingEventRequestDto(
                        eventType,
                        sessionId,
                        "trace-" + sessionId + "-" + eventType.name().toLowerCase(),
                        page,
                        "web",
                        productId,
                        categoryId,
                        orderId,
                        amount,
                        Map.of("keyword", "phone"),
                        LocalDateTime.now()),
                "203.0.113.8");
    }

    private static final class IncrementingIdGenerator implements IdGenerator {
        private final AtomicLong next = new AtomicLong(1000);

        @Override
        public long nextId() {
            return next.incrementAndGet();
        }
    }

    private static final class FakeTrackingStore implements TrackingStore {
        private final List<TrackingEvent> events = new ArrayList<>();
        private final Map<Long, UserProfileTag> userProfiles = new HashMap<>();
        private final Map<Long, ProductProfile> productProfiles = new HashMap<>();

        @Override
        public TrackingEvent saveEvent(TrackingEvent event) {
            events.removeIf(existing -> existing.id().equals(event.id()));
            events.add(event);
            return event;
        }

        @Override
        public List<TrackingEvent> findRecentEvents(LocalDateTime since, int limit) {
            return events.stream()
                    .filter(event -> !event.occurredAt().isBefore(since))
                    .sorted(Comparator.comparing(TrackingEvent::occurredAt).reversed())
                    .limit(Math.max(1, limit))
                    .toList();
        }

        @Override
        public long countEvents(TrackingEventType eventType, LocalDateTime since) {
            return events.stream()
                    .filter(event -> event.eventType() == eventType)
                    .filter(event -> !event.occurredAt().isBefore(since))
                    .count();
        }

        @Override
        public long countDistinctVisitors(LocalDateTime since) {
            return events.stream()
                    .filter(event -> !event.occurredAt().isBefore(since))
                    .map(TrackingEvent::sessionId)
                    .distinct()
                    .count();
        }

        @Override
        public BigDecimal sumPaymentAmount(LocalDateTime since) {
            return events.stream()
                    .filter(event -> event.eventType() == TrackingEventType.PAYMENT_SUCCESS)
                    .filter(event -> !event.occurredAt().isBefore(since))
                    .map(TrackingEvent::amount)
                    .filter(amount -> amount != null)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        @Override
        public Optional<UserProfileTag> findUserProfile(Long userId) {
            return Optional.ofNullable(userProfiles.get(userId));
        }

        @Override
        public UserProfileTag saveUserProfile(UserProfileTag profile) {
            userProfiles.put(profile.userId(), profile);
            return profile;
        }

        @Override
        public Optional<ProductProfile> findProductProfile(Long productId) {
            return Optional.ofNullable(productProfiles.get(productId));
        }

        @Override
        public ProductProfile saveProductProfile(ProductProfile profile) {
            productProfiles.put(profile.productId(), profile);
            return profile;
        }
    }
}
