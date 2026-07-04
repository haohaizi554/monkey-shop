package com.example.monkey.logistics.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.logistics.application.dto.ShipmentCreateRequestDto;
import com.example.monkey.logistics.application.dto.TrackingWebhookRequestDto;
import com.example.monkey.logistics.domain.AddressParser;
import com.example.monkey.logistics.domain.FreightChargeMode;
import com.example.monkey.logistics.domain.FreightTemplate;
import com.example.monkey.logistics.domain.LogisticsCarrier;
import com.example.monkey.logistics.domain.LogisticsGateway;
import com.example.monkey.logistics.domain.LogisticsGatewayResult;
import com.example.monkey.logistics.domain.LogisticsStore;
import com.example.monkey.logistics.domain.LogisticsTracking;
import com.example.monkey.logistics.domain.LogisticsTransitionPolicy;
import com.example.monkey.logistics.domain.LogisticsTransitionResolver;
import com.example.monkey.logistics.domain.LogisticsWebhookReplayGuard;
import com.example.monkey.logistics.domain.ParsedAddress;
import com.example.monkey.logistics.domain.TrackingEvent;
import com.example.monkey.logistics.domain.TrackingEventRecord;
import com.example.monkey.logistics.domain.TrackingStatus;
import com.example.monkey.order.domain.OrderStore;
import com.example.monkey.order.domain.OrderStore.OrderRecord;
import com.example.monkey.shared.application.observability.AuditService;
import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.shared.domain.id.IdGenerator;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LogisticsApplicationServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-07-04T08:30:00Z"), ZoneOffset.UTC);

    @Mock
    private OrderStore orderStore;

    @Mock
    private IdGenerator idGenerator;

    @Mock
    private AuditService auditService;

    private InMemoryLogisticsStore logisticsStore;
    private InMemoryWebhookReplayGuard replayGuard;
    private LogisticsApplicationService service;

    @BeforeEach
    void setUp() {
        logisticsStore = new InMemoryLogisticsStore();
        replayGuard = new InMemoryWebhookReplayGuard();
        service = new LogisticsApplicationService(
                logisticsStore,
                new RecordingLogisticsGateway(),
                replayGuard,
                new PolicyLogisticsTransitionResolver(),
                new StubAddressParser(),
                new com.example.monkey.logistics.domain.FreightCalculator(),
                orderStore,
                idGenerator,
                auditService,
                FIXED_CLOCK,
                Duration.ofHours(24));
    }

    @Test
    void createShipmentCalculatesFreightAndIsIdempotent() {
        when(orderStore.findVisibleByIdAndUserId(10L, 42L)).thenReturn(Optional.of(order()));
        when(idGenerator.nextId()).thenReturn(7000L);

        var first = service.createShipment(user(), request(), "ship-key");
        var replay = service.createShipment(user(), request(), "ship-key");

        assertThat(first.trackingNo()).isEqualTo("SF7000");
        assertThat(first.freightAmount()).isEqualByComparingTo(new BigDecimal("30.00"));
        assertThat(replay.trackingNo()).isEqualTo(first.trackingNo());
        assertThat(logisticsStore.trackings).hasSize(1);
        verify(auditService)
                .record(
                        AuditService.LOGISTICS_SHIPMENT_CREATED,
                        AuditService.OUTCOME_SUCCESS,
                        42L,
                        "CUSTOMER",
                        "SF7000",
                        null,
                        "orderId=10,carrier=SF,freight=30.00");
    }

    @Test
    void webhookAdvancesTrackingOnceForReplayProtectedEvent() {
        when(orderStore.findVisibleByIdAndUserId(10L, 42L)).thenReturn(Optional.of(order()));
        when(idGenerator.nextId()).thenReturn(7000L, 7001L);
        service.createShipment(user(), request(), "ship-key");
        TrackingWebhookRequestDto webhook = new TrackingWebhookRequestDto(
                LogisticsCarrier.SF,
                "SF7000",
                "event-1",
                TrackingEvent.PICKUP,
                LocalDateTime.parse("2026-07-04T09:00:00"),
                "Hangzhou hub",
                "picked up");

        var first = service.handleWebhook(webhook, "127.0.0.1");
        var replay = service.handleWebhook(webhook, "127.0.0.1");

        assertThat(first.status()).isEqualTo(TrackingStatus.PICKED_UP);
        assertThat(replay.status()).isEqualTo(TrackingStatus.PICKED_UP);
        assertThat(logisticsStore.events).hasSize(1);
    }

    private static SessionUser user() {
        return new SessionUser(42L, "USER");
    }

    private static ShipmentCreateRequestDto request() {
        return new ShipmentCreateRequestDto(
                10L,
                LogisticsCarrier.SF,
                "13800138000",
                "Zhejiang Hangzhou Xihu Wenyi Road 100",
                null,
                null,
                null,
                null,
                new BigDecimal("1.20"),
                2);
    }

    private static OrderRecord order() {
        return new OrderRecord(
                10L,
                "ORD202607040001",
                42L,
                "buyer",
                "/images/avatar/buyer.png",
                7L,
                "Momo",
                "/images/product/momo.png",
                new BigDecimal("100.00"),
                "calm",
                "Ada",
                "13800138000",
                "Zhejiang Hangzhou Xihu Wenyi Road 100",
                null,
                "PAID",
                LocalDateTime.parse("2026-07-04T08:00:00"),
                false);
    }

    private static final class StubAddressParser implements AddressParser {
        @Override
        public ParsedAddress parse(String text) {
            return new ParsedAddress("Zhejiang", "Hangzhou", "Xihu", "Wenyi Road 100");
        }
    }

    private static final class PolicyLogisticsTransitionResolver implements LogisticsTransitionResolver {
        @Override
        public TrackingStatus nextStatus(TrackingStatus currentStatus, TrackingEvent event) {
            return LogisticsTransitionPolicy.nextStatus(currentStatus, event)
                    .orElseThrow(() -> new BusinessException(
                            ErrorCode.CONFLICT, LogisticsTransitionPolicy.STATUS_TRANSITION_NOT_ALLOWED));
        }
    }

    private static final class RecordingLogisticsGateway implements LogisticsGateway {
        @Override
        public LogisticsGatewayResult createShipment(LogisticsTracking tracking) {
            return new LogisticsGatewayResult(
                    tracking.carrier(),
                    tracking.trackingNo(),
                    tracking.status(),
                    tracking.etaHours(),
                    tracking.createTime());
        }
    }

    private static final class InMemoryWebhookReplayGuard implements LogisticsWebhookReplayGuard {
        private final List<String> keys = new ArrayList<>();

        @Override
        public boolean reserve(
                LogisticsCarrier carrier, String trackingNo, String eventId, Duration ttl, String sourceIp) {
            String key = carrier + ":" + eventId;
            if (keys.contains(key)) {
                return false;
            }
            keys.add(key);
            return true;
        }
    }

    private static final class InMemoryLogisticsStore implements LogisticsStore {
        private final Map<Long, LogisticsTracking> trackings = new LinkedHashMap<>();
        private final List<TrackingEventRecord> events = new ArrayList<>();

        @Override
        public Optional<LogisticsTracking> findByTrackingNo(String trackingNo) {
            return trackings.values().stream()
                    .filter(tracking -> tracking.trackingNo().equals(trackingNo))
                    .findFirst();
        }

        @Override
        public Optional<LogisticsTracking> findByOrderIdAndUserId(Long orderId, Long userId) {
            return trackings.values().stream()
                    .filter(tracking -> tracking.orderId().equals(orderId)
                            && tracking.userId().equals(userId))
                    .max(Comparator.comparing(LogisticsTracking::createTime));
        }

        @Override
        public Optional<LogisticsTracking> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey) {
            return trackings.values().stream()
                    .filter(tracking -> tracking.userId().equals(userId)
                            && tracking.idempotencyKey().equals(idempotencyKey))
                    .findFirst();
        }

        @Override
        public LogisticsTracking saveTracking(LogisticsTracking tracking) {
            trackings.put(tracking.id(), tracking);
            return tracking;
        }

        @Override
        public TrackingEventRecord saveEvent(TrackingEventRecord event) {
            events.add(event);
            return event;
        }

        @Override
        public List<TrackingEventRecord> findEvents(String trackingNo) {
            return events.stream()
                    .filter(event -> event.trackingNo().equals(trackingNo))
                    .toList();
        }

        @Override
        public List<FreightTemplate> findFreightTemplates(LogisticsCarrier carrier, String province) {
            return List.of(
                    template(FreightChargeMode.WEIGHT, "*", "18.00", "6.00", "0.00", "0.00"),
                    template(FreightChargeMode.ITEM, "*", "0.00", "0.00", "3.00", "0.00"));
        }

        private static FreightTemplate template(
                FreightChargeMode mode,
                String province,
                String baseFee,
                String stepFee,
                String itemFee,
                String regionFee) {
            return new FreightTemplate(
                    1L,
                    LogisticsCarrier.SF,
                    province,
                    mode,
                    BigDecimal.ONE,
                    new BigDecimal(baseFee),
                    BigDecimal.ONE,
                    new BigDecimal(stepFee),
                    new BigDecimal(itemFee),
                    new BigDecimal(regionFee),
                    24,
                    true);
        }
    }
}
