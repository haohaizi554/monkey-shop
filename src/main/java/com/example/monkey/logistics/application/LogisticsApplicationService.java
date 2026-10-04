package com.example.monkey.logistics.application;

import static com.example.monkey.shared.application.security.AuthenticatedPrincipals.requireUserId;

import com.example.monkey.logistics.application.dto.FreightQuoteRequestDto;
import com.example.monkey.logistics.application.dto.FreightQuoteResponseDto;
import com.example.monkey.logistics.application.dto.LogisticsTrackingResponseDto;
import com.example.monkey.logistics.application.dto.ParsedAddressDto;
import com.example.monkey.logistics.application.dto.ShipmentCreateRequestDto;
import com.example.monkey.logistics.application.dto.TrackingWebhookRequestDto;
import com.example.monkey.logistics.domain.AddressParser;
import com.example.monkey.logistics.domain.FreightCalculator;
import com.example.monkey.logistics.domain.FreightQuote;
import com.example.monkey.logistics.domain.LogisticsCarrier;
import com.example.monkey.logistics.domain.LogisticsGateway;
import com.example.monkey.logistics.domain.LogisticsGatewayResult;
import com.example.monkey.logistics.domain.LogisticsStore;
import com.example.monkey.logistics.domain.LogisticsTracking;
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
import com.example.monkey.shared.application.tenant.TenantContext;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.shared.domain.id.IdGenerator;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

@Service
public class LogisticsApplicationService {

    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;
    private static final Pattern IDEMPOTENCY_KEY_PATTERN = Pattern.compile("[A-Za-z0-9._:-]+");
    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final Duration SHIPMENT_CLAIM_LEASE = Duration.ofMinutes(5);
    private static final String CUSTOMER_ROLE = "CUSTOMER";
    private static final String SYSTEM_ROLE = "SYSTEM";
    private static final String SHIPMENT_IN_PROGRESS =
            "Shipment creation is already in progress; retry after the provider call settles";

    private final LogisticsStore logisticsStore;
    private final LogisticsGateway logisticsGateway;
    private final LogisticsWebhookReplayGuard webhookReplayGuard;
    private final LogisticsTransitionResolver transitionResolver;
    private final AddressParser addressParser;
    private final FreightCalculator freightCalculator;
    private final OrderStore orderStore;
    private final OrderFulfillmentPort orderFulfillmentPort;
    private final ShipmentCreationClaimStore shipmentClaimStore;
    private final IdGenerator idGenerator;
    private final AuditService auditService;
    private final Clock clock;
    private final Duration webhookTtl;
    private final String webhookSecret;
    private final TransactionOperations databaseWork;
    private final TransactionOperations outsideDatabase;

    @Autowired
    public LogisticsApplicationService(
            LogisticsStore logisticsStore,
            LogisticsGateway logisticsGateway,
            LogisticsWebhookReplayGuard webhookReplayGuard,
            LogisticsTransitionResolver transitionResolver,
            AddressParser addressParser,
            OrderStore orderStore,
            OrderFulfillmentPort orderFulfillmentPort,
            ShipmentCreationClaimStore shipmentClaimStore,
            IdGenerator idGenerator,
            AuditService auditService,
            PlatformTransactionManager transactionManager,
            @Value("${app.logistics.webhook-ttl:PT24H}") Duration webhookTtl,
            @Value("${app.logistics.webhook-secret:}") String webhookSecret) {
        this(
                logisticsStore,
                logisticsGateway,
                webhookReplayGuard,
                transitionResolver,
                addressParser,
                new FreightCalculator(),
                orderStore,
                orderFulfillmentPort,
                shipmentClaimStore,
                idGenerator,
                auditService,
                Clock.systemDefaultZone(),
                webhookTtl,
                webhookSecret,
                committed(transactionManager),
                suspended(transactionManager));
    }

    LogisticsApplicationService(
            LogisticsStore logisticsStore,
            LogisticsGateway logisticsGateway,
            LogisticsWebhookReplayGuard webhookReplayGuard,
            LogisticsTransitionResolver transitionResolver,
            AddressParser addressParser,
            FreightCalculator freightCalculator,
            OrderStore orderStore,
            OrderFulfillmentPort orderFulfillmentPort,
            ShipmentCreationClaimStore shipmentClaimStore,
            IdGenerator idGenerator,
            AuditService auditService,
            Clock clock,
            Duration webhookTtl,
            String webhookSecret) {
        this(
                logisticsStore,
                logisticsGateway,
                webhookReplayGuard,
                transitionResolver,
                addressParser,
                freightCalculator,
                orderStore,
                orderFulfillmentPort,
                shipmentClaimStore,
                idGenerator,
                auditService,
                clock,
                webhookTtl,
                webhookSecret,
                DIRECT_TRANSACTION,
                DIRECT_TRANSACTION);
    }

    private LogisticsApplicationService(
            LogisticsStore logisticsStore,
            LogisticsGateway logisticsGateway,
            LogisticsWebhookReplayGuard webhookReplayGuard,
            LogisticsTransitionResolver transitionResolver,
            AddressParser addressParser,
            FreightCalculator freightCalculator,
            OrderStore orderStore,
            OrderFulfillmentPort orderFulfillmentPort,
            ShipmentCreationClaimStore shipmentClaimStore,
            IdGenerator idGenerator,
            AuditService auditService,
            Clock clock,
            Duration webhookTtl,
            String webhookSecret,
            TransactionOperations databaseWork,
            TransactionOperations outsideDatabase) {
        this.logisticsStore = logisticsStore;
        this.logisticsGateway = logisticsGateway;
        this.webhookReplayGuard = webhookReplayGuard;
        this.transitionResolver = transitionResolver;
        this.addressParser = addressParser;
        this.freightCalculator = freightCalculator;
        this.orderStore = orderStore;
        this.orderFulfillmentPort = orderFulfillmentPort;
        this.shipmentClaimStore = shipmentClaimStore;
        this.idGenerator = idGenerator;
        this.auditService = auditService;
        this.clock = clock;
        this.webhookTtl = webhookTtl == null ? Duration.ofHours(24) : webhookTtl;
        this.webhookSecret = requireWebhookSecret(webhookSecret);
        this.databaseWork = databaseWork == null ? DIRECT_TRANSACTION : databaseWork;
        this.outsideDatabase = outsideDatabase == null ? DIRECT_TRANSACTION : outsideDatabase;
    }

    private static final TransactionOperations DIRECT_TRANSACTION = new TransactionOperations() {
        @Override
        public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> action) {
            return action.doInTransaction(new org.springframework.transaction.support.SimpleTransactionStatus());
        }
    };

    private static TransactionOperations committed(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    private static TransactionOperations suspended(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        return template;
    }

    @WithSpan("logistics.create")
    public LogisticsTrackingResponseDto createShipment(
            SessionUser currentUser, ShipmentCreateRequestDto request, String idempotencyKey) {
        requireFulfillmentAuthorization(currentUser);
        Long actorUserId = requireUserId(currentUser);
        if (request == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Shipment request is required");
        }
        String key = normalizeIdempotencyKey(idempotencyKey);
        long tenantId = currentTenantId(currentUser);
        OrderRecord order = requireOrder(request.orderId());
        Long ownerUserId = requireOrderOwner(order);
        ParsedAddress canonicalAddress = addressFor(order);
        String requestFingerprint = shipmentFingerprint(tenantId, order, ownerUserId, request, canonicalAddress);
        Optional<ShipmentCreationClaim> currentClaim =
                shipmentClaimStore.findByTenantAndOrder(tenantId, requireOrderId(order));
        if (currentClaim.isPresent()) {
            ShipmentCreationClaim claim =
                    requireClaimCompatible(currentClaim.get(), tenantId, order, ownerUserId, requestFingerprint);
            if (claim.accepted()) {
                return toResponse(requireClaimReplay(claim, order, requestFingerprint));
            }
            if (claim.leaseExpiresAt().isAfter(now())) {
                throw shipmentInProgress();
            }
        }
        var existing = logisticsStore.findByUserIdAndIdempotencyKey(ownerUserId, key);
        if (existing.isPresent()) {
            requireReplayCompatible(existing.get(), order, requestFingerprint);
        }
        // Reject a stale/non-shippable canonical order before creating the durable provider claim.
        orderFulfillmentPort.requireShippable(tenantId, requireOrderId(order));
        FreightQuote quote =
                quote(request.carrier(), canonicalAddress.province(), request.weightKg(), request.itemCount());
        Long requestedShipmentId = idGenerator.nextId();
        LocalDateTime now = now();
        ShipmentCreationClaim candidate = new ShipmentCreationClaim(
                requestedShipmentId,
                tenantId,
                requireOrderId(order),
                ownerUserId,
                key,
                requestFingerprint,
                providerToken(request.carrier(), requestedShipmentId),
                ShipmentClaimStatus.RESERVED,
                UUID.randomUUID().toString(),
                now.plus(SHIPMENT_CLAIM_LEASE),
                now,
                now);
        ShipmentClaimReservation reservation = shipmentClaimStore.reserve(candidate, now, SHIPMENT_CLAIM_LEASE);
        ShipmentCreationClaim claim =
                requireClaimCompatible(reservation.claim(), tenantId, order, ownerUserId, requestFingerprint);
        if (!reservation.acquired()) {
            if (!claim.accepted()) {
                throw shipmentInProgress();
            }
            return toResponse(requireClaimReplay(claim, order, requestFingerprint));
        }
        return createShipmentLocked(
                currentUser, actorUserId, order, request, key, canonicalAddress, quote, requestFingerprint, claim);
    }

    @WithSpan("logistics.find")
    @Transactional(readOnly = true)
    public LogisticsTrackingResponseDto findByOrder(SessionUser currentUser, Long orderId) {
        Long userId = requireUserId(currentUser);
        LogisticsTracking tracking = logisticsStore
                .findByOrderIdAndUserId(orderId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Logistics tracking does not exist"));
        return toResponse(tracking);
    }

    @WithSpan("logistics.find")
    @Transactional(readOnly = true)
    public LogisticsTrackingResponseDto findByTrackingNo(SessionUser currentUser, String trackingNo) {
        Long userId = requireUserId(currentUser);
        LogisticsTracking tracking = requireTracking(trackingNo);
        if (!userId.equals(tracking.userId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Logistics tracking is not available for current user");
        }
        return toResponse(tracking);
    }

    @WithSpan("logistics.webhook")
    @Transactional
    public LogisticsTrackingResponseDto handleWebhook(TrackingWebhookRequestDto request, String sourceIp) {
        verifyWebhookSignature(request);
        LogisticsTracking tracking = requireTracking(request.trackingNo());
        if (request.carrier() != tracking.carrier()) {
            throw new BusinessException(ErrorCode.CONFLICT, "Webhook carrier does not match tracking carrier");
        }
        if (!webhookReplayGuard.reserve(
                request.carrier(), request.trackingNo(), request.eventId(), webhookTtl, sourceIp)) {
            return toResponse(tracking);
        }
        // The replay reservation is a durable, transaction-scoped gate. Reserve first so an identical
        // already-accepted event is idempotent even after the carrier projection has advanced. Any illegal
        // transition below is raised in the same transaction, rolling the reservation back for a retry.
        TrackingStatus nextStatus = transitionResolver.nextStatus(tracking.status(), request.event());
        LocalDateTime eventTime = request.eventTime() == null ? now() : request.eventTime();
        if (nextStatus == TrackingStatus.SIGNED) {
            orderFulfillmentPort.markDelivered(
                    currentTenantId(tracking),
                    tracking.orderId(),
                    requireTrackingId(tracking),
                    eventTime.atZone(clock.getZone()).toInstant());
        }
        LogisticsTracking updated = logisticsStore.saveTracking(tracking.advance(nextStatus, eventTime));
        logisticsStore.saveEvent(new TrackingEventRecord(
                idGenerator.nextId(),
                updated.id(),
                updated.trackingNo(),
                updated.carrier(),
                request.event(),
                tracking.status(),
                nextStatus,
                request.eventId(),
                eventTime,
                request.location(),
                request.remark(),
                now()));
        audit(
                AuditService.LOGISTICS_WEBHOOK_ACCEPTED,
                null,
                updated.trackingNo(),
                sourceIp,
                "event=" + request.event() + ",status=" + nextStatus);
        if (nextStatus == TrackingStatus.SIGNED) {
            audit(
                    AuditService.LOGISTICS_SIGNED,
                    updated.userId(),
                    CUSTOMER_ROLE,
                    updated.trackingNo(),
                    sourceIp,
                    "signed=true");
        }
        return toResponse(updated);
    }

    @WithSpan("logistics.quote")
    @Transactional(readOnly = true)
    public FreightQuoteResponseDto quoteFreight(FreightQuoteRequestDto request) {
        FreightQuote quote = quote(request.carrier(), request.province(), request.weightKg(), request.itemCount());
        audit(
                AuditService.LOGISTICS_FREIGHT_QUOTED,
                null,
                request.carrier() + ":" + request.province(),
                null,
                "amount=" + quote.amount());
        return LogisticsDtoAssembler.toResponse(quote);
    }

    @WithSpan("logistics.address.parse")
    @Transactional(readOnly = true)
    public ParsedAddressDto parseAddress(String text) {
        ParsedAddress address = addressParser.parse(text);
        audit(AuditService.LOGISTICS_ADDRESS_PARSED, null, "address", null, "province=" + address.province());
        return LogisticsDtoAssembler.toResponse(address);
    }

    private LogisticsTrackingResponseDto createShipmentLocked(
            SessionUser currentUser,
            Long actorUserId,
            OrderRecord order,
            ShipmentCreateRequestDto request,
            String idempotencyKey,
            ParsedAddress address,
            FreightQuote quote,
            String requestFingerprint,
            ShipmentCreationClaim claim) {
        Long id = claim.shipmentId();
        LocalDateTime now = now();
        LogisticsTracking tracking = new LogisticsTracking(
                id,
                claim.providerToken(),
                order.id(),
                requireOrderOwner(order),
                request.carrier(),
                TrackingStatus.ORDERED,
                canonicalPhone(order),
                null,
                address.snapshot(),
                null,
                address.province(),
                address.city(),
                address.district(),
                address.summary(),
                money(quote.amount()),
                quote.etaHours(),
                idempotencyKey,
                null,
                null,
                null,
                null,
                now,
                now,
                requestFingerprint);
        LogisticsTracking persisted = databaseWork.execute(status -> logisticsStore.saveTracking(tracking));
        LogisticsGatewayResult gatewayResult;
        try {
            gatewayResult = outsideDatabase.execute(status -> logisticsGateway.createShipment(persisted));
        } catch (RuntimeException failure) {
            abandonUnacceptedShipment(persisted, claim, now(), failure);
            throw failure;
        }
        try {
            return databaseWork.execute(status -> {
                orderFulfillmentPort.markShipmentCreated(currentTenantId(currentUser), requireOrderId(order), id);
                LogisticsTracking accepted =
                        logisticsStore.saveTracking(persisted.withGatewayResult(gatewayResult, now()));
                shipmentClaimStore.markAccepted(claim, now());
                audit(
                        AuditService.LOGISTICS_SHIPMENT_CREATED,
                        actorUserId,
                        auditRole(currentUser),
                        accepted.trackingNo(),
                        null,
                        "orderId="
                                + order.id()
                                + ",carrier="
                                + accepted.carrier()
                                + ",freight="
                                + accepted.freightAmount());
                return toResponse(accepted);
            });
        } catch (RuntimeException failure) {
            abandonUnacceptedShipment(persisted, claim, now(), failure);
            throw failure;
        }
    }

    private void abandonUnacceptedShipment(
            LogisticsTracking tracking, ShipmentCreationClaim claim, LocalDateTime now, RuntimeException failure) {
        try {
            databaseWork.executeWithoutResult(status -> logisticsStore.deleteTracking(tracking.id()));
        } catch (RuntimeException deleteFailure) {
            failure.addSuppressed(deleteFailure);
        }
        try {
            shipmentClaimStore.releaseForRetry(claim, now);
        } catch (RuntimeException releaseFailure) {
            failure.addSuppressed(releaseFailure);
        }
    }

    private FreightQuote quote(
            com.example.monkey.logistics.domain.LogisticsCarrier carrier,
            String province,
            BigDecimal weightKg,
            int itemCount) {
        return freightCalculator.quote(
                carrier, province, money(weightKg), itemCount, logisticsStore.findFreightTemplates(carrier, province));
    }

    private static LogisticsTracking requireReplayCompatible(
            LogisticsTracking existing, OrderRecord order, String expectedFingerprint) {
        if (existing == null
                || !Long.valueOf(order.id()).equals(existing.orderId())
                || !expectedFingerprint.equals(existing.requestFingerprint())) {
            throw new BusinessException(
                    ErrorCode.CONFLICT, "Idempotency-Key is already bound to a different shipment request");
        }
        return existing;
    }

    private static ShipmentCreationClaim requireClaimCompatible(
            ShipmentCreationClaim claim,
            long tenantId,
            OrderRecord order,
            long ownerUserId,
            String requestFingerprint) {
        if (claim == null || !claim.sameRequest(tenantId, requireOrderId(order), ownerUserId, requestFingerprint)) {
            throw new BusinessException(ErrorCode.CONFLICT, "Shipment creation claim is bound to a different request");
        }
        return claim;
    }

    private LogisticsTracking requireClaimReplay(
            ShipmentCreationClaim claim, OrderRecord order, String requestFingerprint) {
        if (claim == null || !claim.accepted()) {
            throw shipmentInProgress();
        }
        LogisticsTracking tracking = logisticsStore
                .findByTrackingNo(claim.providerToken())
                .orElseThrow(LogisticsApplicationService::shipmentInProgress);
        return requireReplayCompatible(tracking, order, requestFingerprint);
    }

    private static BusinessException shipmentInProgress() {
        return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, SHIPMENT_IN_PROGRESS);
    }

    private static String shipmentFingerprint(
            long tenantId,
            OrderRecord order,
            long ownerUserId,
            ShipmentCreateRequestDto request,
            ParsedAddress canonicalAddress) {
        String payload = String.join(
                "\u001f",
                Long.toString(tenantId),
                Long.toString(requireOrderId(order)),
                Long.toString(ownerUserId),
                request.carrier() == null ? "" : request.carrier().name(),
                money(request.weightKg()).toPlainString(),
                Integer.toString(request.itemCount()),
                canonicalPhone(order),
                canonical(order.receiverPhone()),
                canonical(order.addressSnapshot()),
                canonicalAddress == null ? "" : canonical(canonicalAddress.snapshot()),
                canonicalAddress == null ? "" : canonical(canonicalAddress.province()),
                canonicalAddress == null ? "" : canonical(canonicalAddress.city()),
                canonicalAddress == null ? "" : canonical(canonicalAddress.district()),
                canonicalAddress == null ? "" : canonical(canonicalAddress.detail()));
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Shipment request fingerprint could not be initialized", exception);
        }
    }

    private static String providerToken(LogisticsCarrier carrier, long shipmentId) {
        if (carrier == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "carrier is required");
        }
        return carrier.name() + shipmentId;
    }

    private ParsedAddress addressFor(OrderRecord order) {
        if (!StringUtils.hasText(order.addressSnapshot())) {
            throw new BusinessException(ErrorCode.CONFLICT, "Order recipient address is missing");
        }
        ParsedAddress parsed = addressParser.parse(order.addressSnapshot().trim());
        if (parsed == null || !StringUtils.hasText(parsed.province()) || !StringUtils.hasText(parsed.city())) {
            throw new BusinessException(ErrorCode.CONFLICT, "Order recipient address is invalid");
        }
        return parsed;
    }

    private OrderRecord requireOrder(Long orderId) {
        if (orderId == null || orderId <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "orderId is required");
        }
        return orderStore
                .findById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Order does not exist"));
    }

    private static Long requireOrderOwner(OrderRecord order) {
        if (order == null || order.id() == null || order.userId() == null || order.userId() <= 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "Order owner is missing");
        }
        return order.userId();
    }

    private static Long requireOrderId(OrderRecord order) {
        if (order == null || order.id() == null || order.id() <= 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "Order identity is missing");
        }
        return order.id();
    }

    private static String canonicalPhone(OrderRecord order) {
        if (order == null || !StringUtils.hasText(order.receiverPhone())) {
            throw new BusinessException(ErrorCode.CONFLICT, "Order recipient phone is missing");
        }
        return order.receiverPhone().trim();
    }

    private static long currentTenantId(SessionUser currentUser) {
        if (currentUser == null || currentUser.tenantId() == null || currentUser.tenantId() <= 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return currentUser.tenantId();
    }

    private static long currentTenantId(LogisticsTracking tracking) {
        if (tracking == null || tracking.orderId() == null || tracking.orderId() <= 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "Logistics tracking order is missing");
        }
        return TenantContext.currentTenantIdOrDefault();
    }

    private static long requireTrackingId(LogisticsTracking tracking) {
        if (tracking == null || tracking.id() == null || tracking.id() <= 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "Logistics tracking identity is missing");
        }
        return tracking.id();
    }

    private static void requireFulfillmentAuthorization(SessionUser currentUser) {
        requireUserId(currentUser);
        if ("ADMIN".equalsIgnoreCase(trim(currentUser.role())) || hasAuthority("ORDER_MANAGE")) {
            return;
        }
        throw new BusinessException(ErrorCode.FORBIDDEN, "Order management authority is required");
    }

    private static boolean hasAuthority(String authority) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .anyMatch(grantedAuthority -> authority.equals(grantedAuthority.getAuthority()));
    }

    private LogisticsTracking requireTracking(String trackingNo) {
        if (!StringUtils.hasText(trackingNo)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "trackingNo is required");
        }
        return logisticsStore
                .findByTrackingNo(trackingNo.trim())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Logistics tracking does not exist"));
    }

    private LogisticsTrackingResponseDto toResponse(LogisticsTracking tracking) {
        return LogisticsDtoAssembler.toResponse(tracking, logisticsStore.findEvents(tracking.trackingNo()));
    }

    private void audit(String eventType, Long actorUserId, String subject, String sourceIp, String detail) {
        audit(eventType, actorUserId, actorUserId == null ? SYSTEM_ROLE : CUSTOMER_ROLE, subject, sourceIp, detail);
    }

    private void audit(
            String eventType, Long actorUserId, String actorRole, String subject, String sourceIp, String detail) {
        auditService.record(eventType, AuditService.OUTCOME_SUCCESS, actorUserId, actorRole, subject, sourceIp, detail);
    }

    private static String auditRole(SessionUser currentUser) {
        String role = trim(currentUser == null ? null : currentUser.role());
        return StringUtils.hasText(role) ? role.toUpperCase(Locale.ROOT) : "FULFILLMENT";
    }

    private static String normalizeIdempotencyKey(String idempotencyKey) {
        if (!StringUtils.hasText(idempotencyKey)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Idempotency-Key header is required");
        }
        String normalized = idempotencyKey.trim();
        if (normalized.length() > MAX_IDEMPOTENCY_KEY_LENGTH
                || !IDEMPOTENCY_KEY_PATTERN.matcher(normalized).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Idempotency-Key header is invalid");
        }
        return normalized;
    }

    private void verifyWebhookSignature(TrackingWebhookRequestDto request) {
        if (!StringUtils.hasText(request.signature())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Logistics webhook signature is invalid");
        }
        String expected = signature(
                TenantContext.currentTenantIdOrDefault(),
                request.carrier(),
                request.trackingNo(),
                request.eventId(),
                request.event(),
                request.eventTime(),
                request.location(),
                request.remark(),
                webhookSecret);
        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                request.signature().trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Logistics webhook signature is invalid");
        }
    }

    public static String signature(
            com.example.monkey.logistics.domain.LogisticsCarrier carrier,
            String trackingNo,
            String eventId,
            TrackingEvent event,
            LocalDateTime eventTime,
            String location,
            String remark,
            String secret) {
        return signature(
                TenantContext.PLATFORM_TENANT_ID,
                carrier,
                trackingNo,
                eventId,
                event,
                eventTime,
                location,
                remark,
                secret);
    }

    /** Build the carrier signature with tenant identity in the MAC input. */
    public static String signature(
            long tenantId,
            com.example.monkey.logistics.domain.LogisticsCarrier carrier,
            String trackingNo,
            String eventId,
            TrackingEvent event,
            LocalDateTime eventTime,
            String location,
            String remark,
            String secret) {
        if (tenantId <= 0) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Webhook tenant is invalid");
        }
        return hmacSha256Hex(
                secret,
                String.join(
                        ":",
                        Long.toString(tenantId),
                        carrier == null ? "" : carrier.name(),
                        canonical(trackingNo),
                        canonical(eventId),
                        event == null ? "" : event.name(),
                        eventTime == null ? "" : eventTime.toString(),
                        canonical(location),
                        canonical(remark)));
    }

    private static String hmacSha256Hex(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(requireWebhookSecret(secret).getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException exception) {
            throw new IllegalStateException("Logistics webhook HMAC could not be initialized", exception);
        }
    }

    private static String requireWebhookSecret(String secret) {
        if (!StringUtils.hasText(secret)) {
            throw new IllegalStateException("APP_LOGISTICS_WEBHOOK_SECRET must be set");
        }
        return secret.trim();
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static String canonical(String value) {
        return value == null ? "" : value.trim();
    }
}
