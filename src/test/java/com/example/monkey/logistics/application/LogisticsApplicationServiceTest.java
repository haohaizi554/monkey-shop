package com.example.monkey.logistics.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.logistics.application.dto.ShipmentCreateRequestDto;
import com.example.monkey.logistics.application.dto.LogisticsTrackingResponseDto;
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
import com.example.monkey.logistics.domain.OrderFulfillmentPort;
import com.example.monkey.logistics.domain.ParsedAddress;
import com.example.monkey.logistics.domain.ShipmentClaimReservation;
import com.example.monkey.logistics.domain.ShipmentClaimStatus;
import com.example.monkey.logistics.domain.ShipmentCreationClaim;
import com.example.monkey.logistics.domain.ShipmentCreationClaimStore;
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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import com.example.monkey.shared.application.tenant.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LogisticsApplicationServiceTest {

    private static final String WEBHOOK_SECRET = "logistics-secret";
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-07-04T08:30:00Z"), ZoneOffset.UTC);

    @Mock
    private OrderStore orderStore;

    @Mock
    private OrderFulfillmentPort orderFulfillmentPort;

    @Mock
    private IdGenerator idGenerator;

    @Mock
    private AuditService auditService;

    private InMemoryLogisticsStore logisticsStore;
    private InMemoryShipmentClaimStore shipmentClaimStore;
    private RecordingLogisticsGateway gateway;
    private InMemoryWebhookReplayGuard replayGuard;
    private LogisticsApplicationService service;

    @BeforeEach
    void setUp() {
        logisticsStore = new InMemoryLogisticsStore();
        shipmentClaimStore = new InMemoryShipmentClaimStore();
        gateway = new RecordingLogisticsGateway();
        replayGuard = new InMemoryWebhookReplayGuard();
        service = new LogisticsApplicationService(
                logisticsStore,
                gateway,
                replayGuard,
                new PolicyLogisticsTransitionResolver(),
                new StubAddressParser(),
                new com.example.monkey.logistics.domain.FreightCalculator(),
                orderStore,
                orderFulfillmentPort,
                shipmentClaimStore,
                idGenerator,
                auditService,
                FIXED_CLOCK,
                Duration.ofHours(24),
                WEBHOOK_SECRET);
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void createShipmentCalculatesFreightAndIsIdempotent() {
        when(orderStore.findById(10L)).thenReturn(Optional.of(order()));
        when(idGenerator.nextId()).thenReturn(7000L);

        var first = service.createShipment(admin(), request(), "ship-key");
        var replay = service.createShipment(admin(), request(), "ship-key");

        assertThat(first.trackingNo()).isEqualTo("SF7000");
        assertThat(first.freightAmount()).isEqualByComparingTo(new BigDecimal("30.00"));
        assertThat(replay.trackingNo()).isEqualTo(first.trackingNo());
        assertThat(logisticsStore.trackings).hasSize(1);
        verify(auditService)
                .record(
                        AuditService.LOGISTICS_SHIPMENT_CREATED,
                        AuditService.OUTCOME_SUCCESS,
                        42L,
                        "ADMIN",
                        "SF7000",
                        null,
                        "orderId=10,carrier=SF,freight=30.00");
    }

    @Test
    void concurrentDifferentIdempotencyKeysShareOneDurableProviderClaim() throws Exception {
        when(orderStore.findById(10L)).thenReturn(Optional.of(order()));
        when(idGenerator.nextId()).thenReturn(7000L, 7001L);
        gateway.blockProviderCall();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<LogisticsTrackingResponseDto> first = executor.submit(
                    () -> service.createShipment(admin(), request(), "ship-key-1"));
            assertThat(gateway.providerEntered.await(5, TimeUnit.SECONDS)).isTrue();
            Future<LogisticsTrackingResponseDto> second = executor.submit(
                    () -> service.createShipment(admin(), request(), "ship-key-2"));
            LogisticsTrackingResponseDto secondResult = second.get(5, TimeUnit.SECONDS);
            gateway.allowProviderToReturn.countDown();
            LogisticsTrackingResponseDto firstResult = first.get(5, TimeUnit.SECONDS);

            assertThat(firstResult.trackingNo()).isEqualTo(secondResult.trackingNo());
            assertThat(gateway.createCalls).isEqualTo(1);
            assertThat(logisticsStore.trackings).hasSize(1);
        } finally {
            gateway.allowProviderToReturn.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void providerTimeoutCanRetryWithTheSameStableProviderToken() {
        when(orderStore.findById(10L)).thenReturn(Optional.of(order()));
        when(idGenerator.nextId()).thenReturn(7000L, 7001L);
        gateway.failNextProviderCall();

        assertThatThrownBy(() -> service.createShipment(admin(), request(), "ship-timeout"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
        // The real transaction rolls the projection back when the provider call fails. Reproduce that boundary in
        // this unit test while retaining the independently committed claim.
        logisticsStore.trackings.clear();

        LogisticsTrackingResponseDto retried = service.createShipment(admin(), request(), "ship-timeout");

        assertThat(retried.trackingNo()).isEqualTo("SF7000");
        assertThat(gateway.createCalls).isEqualTo(2);
        assertThat(gateway.providerTokens).containsExactly("SF7000", "SF7000");
    }

    @Test
    void changedPayloadUnderTheSameIdempotencyKeyIsRejected() {
        when(orderStore.findById(10L)).thenReturn(Optional.of(order()));
        when(idGenerator.nextId()).thenReturn(7000L);
        service.createShipment(admin(), request(), "ship-key");

        ShipmentCreateRequestDto changed = new ShipmentCreateRequestDto(
                10L,
                LogisticsCarrier.SF,
                request().recipientPhone(),
                request().addressText(),
                null,
                null,
                null,
                null,
                new BigDecimal("2.20"),
                request().itemCount());

        assertThatThrownBy(() -> service.createShipment(admin(), changed, "ship-key"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        assertThat(gateway.createCalls).isEqualTo(1);
    }

    @Test
    void migratedLegacyClaimBlocksNewIdempotencyKeyBeforeProviderCall() {
        when(orderStore.findById(10L)).thenReturn(Optional.of(order()));
        when(idGenerator.nextId()).thenReturn(7001L);
        shipmentClaimStore.seedAccepted(new ShipmentCreationClaim(
                7000L,
                1L,
                10L,
                42L,
                "legacy-key",
                "0".repeat(64),
                "SF7000",
                ShipmentClaimStatus.ACCEPTED,
                "legacy-logistics-7000",
                LocalDateTime.parse("2026-07-04T08:00:00"),
                LocalDateTime.parse("2026-07-04T08:00:00"),
                LocalDateTime.parse("2026-07-04T08:00:00")));

        assertThatThrownBy(() -> service.createShipment(admin(), request(), "new-key"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        assertThat(gateway.createCalls).isZero();
    }

    @Test
    void webhookSignatureCannotBeReplayedAcrossTenants() {
        TrackingWebhookRequestDto request = webhook(
                LogisticsCarrier.SF,
                "SF7000",
                "tenant-event",
                TrackingEvent.PICKUP,
                LocalDateTime.parse("2026-07-04T09:00:00"),
                "Hangzhou hub",
                "picked up");
        TenantContext.setTenantId(2L);

        assertThatThrownBy(() -> service.handleWebhook(request, "127.0.0.1"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void customerCannotCreateShipmentEvenWhenOrderIsVisible() {
        assertThatThrownBy(() -> service.createShipment(user(), request(), "ship-key"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThat(logisticsStore.trackings).isEmpty();
    }

    @Test
    void shipmentUsesCanonicalRecipientInsteadOfRequestOverrides() {
        when(orderStore.findById(10L)).thenReturn(Optional.of(order()));
        when(idGenerator.nextId()).thenReturn(7000L);

        ShipmentCreateRequestDto forgedRequest = new ShipmentCreateRequestDto(
                10L,
                LogisticsCarrier.SF,
                "00000000000",
                "FORGED recipient address",
                null,
                null,
                null,
                null,
                new BigDecimal("1.20"),
                2);

        service.createShipment(admin(), forgedRequest, "ship-key");

        LogisticsTracking saved = logisticsStore.trackings.get(7000L);
        assertThat(saved.recipientPhone()).isEqualTo(order().receiverPhone());
        assertThat(saved.addressSnapshot()).isEqualTo(order().addressSnapshot());
    }

    @Test
    void shipmentCreationRejectsOrdersOutsidePaidStates() {
        when(orderStore.findById(10L)).thenReturn(Optional.of(order("PENDING_PAYMENT")));
        doThrow(new BusinessException(ErrorCode.CONFLICT, "Order status does not allow this operation"))
                .when(orderFulfillmentPort)
                .requireShippable(1L, 10L);

        assertThatThrownBy(() -> service.createShipment(admin(), request(), "ship-key"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        assertThat(logisticsStore.trackings).isEmpty();
    }

    @Test
    void webhookAdvancesTrackingOnceForReplayProtectedEvent() {
        when(orderStore.findById(10L)).thenReturn(Optional.of(order()));
        when(idGenerator.nextId()).thenReturn(7000L, 7001L);
        service.createShipment(admin(), request(), "ship-key");
        TrackingWebhookRequestDto webhook = webhook(
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

    @Test
    void webhookRejectsInvalidSignatureBeforeStateChange() {
        when(orderStore.findById(10L)).thenReturn(Optional.of(order()));
        when(idGenerator.nextId()).thenReturn(7000L);
        service.createShipment(admin(), request(), "ship-key");
        TrackingWebhookRequestDto webhook = new TrackingWebhookRequestDto(
                LogisticsCarrier.SF,
                "SF7000",
                "event-1",
                TrackingEvent.PICKUP,
                LocalDateTime.parse("2026-07-04T09:00:00"),
                "Hangzhou hub",
                "picked up",
                "bad-signature");

        assertThatThrownBy(() -> service.handleWebhook(webhook, "127.0.0.1"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThat(logisticsStore.findByTrackingNo("SF7000"))
                .get()
                .extracting(LogisticsTracking::status)
                .isEqualTo(TrackingStatus.ORDERED);
        assertThat(logisticsStore.events).isEmpty();
    }

    @Test
    void signedWebhookAdvancesCanonicalOrderOnceAndReplayIsIdempotent() {
        when(orderStore.findById(10L)).thenReturn(Optional.of(order()));
        when(idGenerator.nextId()).thenReturn(7000L, 7001L, 7002L, 7003L, 7004L);
        service.createShipment(admin(), request(), "ship-key");

        service.handleWebhook(
                webhook(
                        LogisticsCarrier.SF,
                        "SF7000",
                        "pickup-1",
                        TrackingEvent.PICKUP,
                        LocalDateTime.parse("2026-07-04T09:00:00"),
                        "Hangzhou hub",
                        "picked up"),
                "127.0.0.1");
        service.handleWebhook(
                webhook(
                        LogisticsCarrier.SF,
                        "SF7000",
                        "transit-1",
                        TrackingEvent.TRANSIT,
                        LocalDateTime.parse("2026-07-04T10:00:00"),
                        "Hangzhou hub",
                        "in transit"),
                "127.0.0.1");
        service.handleWebhook(
                webhook(
                        LogisticsCarrier.SF,
                        "SF7000",
                        "delivery-1",
                        TrackingEvent.DISPATCH,
                        LocalDateTime.parse("2026-07-04T11:00:00"),
                        "Xihu",
                        "out for delivery"),
                "127.0.0.1");
        TrackingWebhookRequestDto signed = webhook(
                LogisticsCarrier.SF,
                "SF7000",
                "signed-1",
                TrackingEvent.SIGN,
                LocalDateTime.parse("2026-07-04T12:00:00"),
                "Wenyi Road 100",
                "received");

        var first = service.handleWebhook(signed, "127.0.0.1");
        var replay = service.handleWebhook(signed, "127.0.0.1");

        assertThat(first.status()).isEqualTo(TrackingStatus.SIGNED);
        assertThat(replay.status()).isEqualTo(TrackingStatus.SIGNED);
        verify(orderFulfillmentPort)
                .markDelivered(1L, 10L, 7000L, Instant.parse("2026-07-04T12:00:00Z"));
    }

    @Test
    void illegalAndReverseWebhookEventsDoNotAdvanceTrackingOrCanonicalOrder() {
        when(orderStore.findById(10L)).thenReturn(Optional.of(order()));
        when(idGenerator.nextId()).thenReturn(7000L, 7001L);
        service.createShipment(admin(), request(), "ship-key");

        assertThatThrownBy(() -> service.handleWebhook(
                        webhook(
                                LogisticsCarrier.SF,
                                "SF7000",
                                "illegal-sign-1",
                                TrackingEvent.SIGN,
                                LocalDateTime.parse("2026-07-04T09:00:00"),
                                "Xihu",
                                "forged sign"),
                        "127.0.0.1"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.CONFLICT));

        service.handleWebhook(
                webhook(
                        LogisticsCarrier.SF,
                        "SF7000",
                        "pickup-1",
                        TrackingEvent.PICKUP,
                        LocalDateTime.parse("2026-07-04T10:00:00"),
                        "Hangzhou hub",
                        "picked up"),
                "127.0.0.1");
        assertThatThrownBy(() -> service.handleWebhook(
                        webhook(
                                LogisticsCarrier.SF,
                                "SF7000",
                                "reverse-pickup-1",
                                TrackingEvent.PICKUP,
                                LocalDateTime.parse("2026-07-04T11:00:00"),
                                "Hangzhou hub",
                                "replayed out of order"),
                        "127.0.0.1"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.CONFLICT));

        assertThat(logisticsStore.findByTrackingNo("SF7000"))
                .get()
                .extracting(LogisticsTracking::status)
                .isEqualTo(TrackingStatus.PICKED_UP);
        assertThat(logisticsStore.events).hasSize(1);
        verify(orderFulfillmentPort, never()).markDelivered(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void webhookCarrierMismatchFailsBeforeReplayReservation() {
        when(orderStore.findById(10L)).thenReturn(Optional.of(order()));
        when(idGenerator.nextId()).thenReturn(7000L);
        service.createShipment(admin(), request(), "ship-key");

        TrackingWebhookRequestDto mismatched = webhook(
                LogisticsCarrier.YTO,
                "SF7000",
                "event-mismatch",
                TrackingEvent.PICKUP,
                LocalDateTime.parse("2026-07-04T09:00:00"),
                "Hangzhou hub",
                "wrong carrier");

        assertThatThrownBy(() -> service.handleWebhook(mismatched, "127.0.0.1"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        assertThat(logisticsStore.events).isEmpty();
    }

    private static SessionUser user() {
        return new SessionUser(42L, "USER");
    }

    private static SessionUser admin() {
        return new SessionUser(42L, "ADMIN");
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
        return order("PAID");
    }

    private static OrderRecord order(String status) {
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
                status,
                LocalDateTime.parse("2026-07-04T08:00:00"),
                false);
    }

    private static TrackingWebhookRequestDto webhook(
            LogisticsCarrier carrier,
            String trackingNo,
            String eventId,
            TrackingEvent event,
            LocalDateTime eventTime,
            String location,
            String remark) {
        String signature = LogisticsApplicationService.signature(
                carrier, trackingNo, eventId, event, eventTime, location, remark, WEBHOOK_SECRET);
        return new TrackingWebhookRequestDto(
                carrier, trackingNo, eventId, event, eventTime, location, remark, signature);
    }

    private static final class StubAddressParser implements AddressParser {
        @Override
        public ParsedAddress parse(String text) {
            if (text != null && text.contains("FORGED")) {
                return new ParsedAddress("AttackerProvince", "AttackerCity", "AttackerDistrict", "Attacker address");
            }
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
        private int createCalls;
        private final List<String> providerTokens = new ArrayList<>();
        private CountDownLatch providerEntered;
        private CountDownLatch allowProviderToReturn;
        private boolean failNext;

        private void blockProviderCall() {
            providerEntered = new CountDownLatch(1);
            allowProviderToReturn = new CountDownLatch(1);
        }

        private void failNextProviderCall() {
            failNext = true;
        }

        @Override
        public LogisticsGatewayResult createShipment(LogisticsTracking tracking) {
            createCalls++;
            providerTokens.add(tracking.trackingNo());
            if (providerEntered != null) {
                providerEntered.countDown();
                try {
                    if (!allowProviderToReturn.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("provider test gate timed out");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
            }
            if (failNext) {
                failNext = false;
                throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "provider timeout");
            }
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

    private static final class InMemoryShipmentClaimStore implements ShipmentCreationClaimStore {
        private final Map<Long, ShipmentCreationClaim> byOrder = new LinkedHashMap<>();
        private final Map<String, ShipmentCreationClaim> byKey = new LinkedHashMap<>();

        private synchronized void seedAccepted(ShipmentCreationClaim claim) {
            byOrder.put(claim.orderId(), claim);
            byKey.put(claim.ownerUserId() + ":" + claim.idempotencyKey(), claim);
        }

        @Override
        public synchronized ShipmentClaimReservation reserve(
                ShipmentCreationClaim candidate, LocalDateTime now, Duration leaseDuration) {
            ShipmentCreationClaim keyClaim = byKey.get(candidate.ownerUserId() + ":" + candidate.idempotencyKey());
            ShipmentCreationClaim orderClaim = byOrder.get(candidate.orderId());
            if (keyClaim != null && (!keyClaim.sameOrder(candidate.tenantId(), candidate.orderId())
                    || !keyClaim.requestFingerprint().equals(candidate.requestFingerprint()))) {
                throw new BusinessException(ErrorCode.CONFLICT, "Idempotency-Key is already bound");
            }
            if (orderClaim != null && (!orderClaim.sameRequest(
                    candidate.tenantId(), candidate.orderId(), candidate.ownerUserId(), candidate.requestFingerprint()))) {
                throw new BusinessException(ErrorCode.CONFLICT, "Order already has a different shipment request");
            }
            ShipmentCreationClaim existing = keyClaim == null ? orderClaim : keyClaim;
            if (existing == null) {
                ShipmentCreationClaim reserved = new ShipmentCreationClaim(
                        candidate.shipmentId(),
                        candidate.tenantId(),
                        candidate.orderId(),
                        candidate.ownerUserId(),
                        candidate.idempotencyKey(),
                        candidate.requestFingerprint(),
                        candidate.providerToken(),
                        ShipmentClaimStatus.RESERVED,
                        candidate.claimToken(),
                        now.plus(leaseDuration),
                        candidate.createTime(),
                        now);
                byOrder.put(reserved.orderId(), reserved);
                byKey.put(reserved.ownerUserId() + ":" + reserved.idempotencyKey(), reserved);
                return new ShipmentClaimReservation(reserved, true);
            }
            if (existing.accepted() || existing.leaseExpiresAt().isAfter(now)) {
                return new ShipmentClaimReservation(existing, false);
            }
            ShipmentCreationClaim renewed = existing.withLease(
                    UUID.randomUUID().toString(), now.plus(leaseDuration), now);
            byOrder.put(renewed.orderId(), renewed);
            byKey.put(renewed.ownerUserId() + ":" + renewed.idempotencyKey(), renewed);
            return new ShipmentClaimReservation(renewed, true);
        }

        @Override
        public synchronized void releaseForRetry(ShipmentCreationClaim claim, LocalDateTime now) {
            ShipmentCreationClaim current = byOrder.get(claim.orderId());
            if (current != null && current.claimToken().equals(claim.claimToken())) {
                ShipmentCreationClaim released = new ShipmentCreationClaim(
                        current.shipmentId(),
                        current.tenantId(),
                        current.orderId(),
                        current.ownerUserId(),
                        current.idempotencyKey(),
                        current.requestFingerprint(),
                        current.providerToken(),
                        current.status(),
                        current.claimToken(),
                        now,
                        current.createTime(),
                        now);
                byOrder.put(released.orderId(), released);
                byKey.put(released.ownerUserId() + ":" + released.idempotencyKey(), released);
            }
        }

        @Override
        public synchronized void markAccepted(ShipmentCreationClaim claim, LocalDateTime now) {
            ShipmentCreationClaim current = byOrder.get(claim.orderId());
            if (current == null || !current.claimToken().equals(claim.claimToken())) {
                throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "claim lost");
            }
            ShipmentCreationClaim accepted = current.acceptedAt(now);
            byOrder.put(accepted.orderId(), accepted);
            byKey.put(accepted.ownerUserId() + ":" + accepted.idempotencyKey(), accepted);
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
        public void deleteTracking(Long trackingId) {
            trackings.remove(trackingId);
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
