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
import com.example.monkey.logistics.domain.LogisticsGateway;
import com.example.monkey.logistics.domain.LogisticsGatewayResult;
import com.example.monkey.logistics.domain.LogisticsStore;
import com.example.monkey.logistics.domain.LogisticsTracking;
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
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class LogisticsApplicationService {

    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;
    private static final Pattern IDEMPOTENCY_KEY_PATTERN = Pattern.compile("[A-Za-z0-9._:-]+");
    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final String CUSTOMER_ROLE = "CUSTOMER";
    private static final String SYSTEM_ROLE = "SYSTEM";

    private final LogisticsStore logisticsStore;
    private final LogisticsGateway logisticsGateway;
    private final LogisticsWebhookReplayGuard webhookReplayGuard;
    private final LogisticsTransitionResolver transitionResolver;
    private final AddressParser addressParser;
    private final FreightCalculator freightCalculator;
    private final OrderStore orderStore;
    private final IdGenerator idGenerator;
    private final AuditService auditService;
    private final Clock clock;
    private final Duration webhookTtl;
    private final String webhookSecret;

    @Autowired
    public LogisticsApplicationService(
            LogisticsStore logisticsStore,
            LogisticsGateway logisticsGateway,
            LogisticsWebhookReplayGuard webhookReplayGuard,
            LogisticsTransitionResolver transitionResolver,
            AddressParser addressParser,
            OrderStore orderStore,
            IdGenerator idGenerator,
            AuditService auditService,
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
                idGenerator,
                auditService,
                Clock.systemDefaultZone(),
                webhookTtl,
                webhookSecret);
    }

    LogisticsApplicationService(
            LogisticsStore logisticsStore,
            LogisticsGateway logisticsGateway,
            LogisticsWebhookReplayGuard webhookReplayGuard,
            LogisticsTransitionResolver transitionResolver,
            AddressParser addressParser,
            FreightCalculator freightCalculator,
            OrderStore orderStore,
            IdGenerator idGenerator,
            AuditService auditService,
            Clock clock,
            Duration webhookTtl,
            String webhookSecret) {
        this.logisticsStore = logisticsStore;
        this.logisticsGateway = logisticsGateway;
        this.webhookReplayGuard = webhookReplayGuard;
        this.transitionResolver = transitionResolver;
        this.addressParser = addressParser;
        this.freightCalculator = freightCalculator;
        this.orderStore = orderStore;
        this.idGenerator = idGenerator;
        this.auditService = auditService;
        this.clock = clock;
        this.webhookTtl = webhookTtl == null ? Duration.ofHours(24) : webhookTtl;
        this.webhookSecret = requireWebhookSecret(webhookSecret);
    }

    @WithSpan("logistics.create")
    @Transactional
    public LogisticsTrackingResponseDto createShipment(
            SessionUser currentUser, ShipmentCreateRequestDto request, String idempotencyKey) {
        Long userId = requireUserId(currentUser);
        String key = normalizeIdempotencyKey(idempotencyKey);
        return logisticsStore
                .findByUserIdAndIdempotencyKey(userId, key)
                .map(this::toResponse)
                .orElseGet(() -> createShipmentLocked(userId, request, key));
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
        TrackingStatus nextStatus = transitionResolver.nextStatus(tracking.status(), request.event());
        LocalDateTime eventTime = request.eventTime() == null ? now() : request.eventTime();
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
            audit(AuditService.LOGISTICS_SIGNED, updated.userId(), updated.trackingNo(), sourceIp, "signed=true");
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
            Long userId, ShipmentCreateRequestDto request, String idempotencyKey) {
        OrderRecord order = orderStore
                .findVisibleByIdAndUserId(request.orderId(), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN, "Order is not available for logistics"));
        ParsedAddress address = addressFor(request, order);
        FreightQuote quote = quote(request.carrier(), address.province(), request.weightKg(), request.itemCount());
        Long id = idGenerator.nextId();
        LocalDateTime now = now();
        LogisticsTracking tracking = new LogisticsTracking(
                id,
                request.carrier().name() + id,
                order.id(),
                userId,
                request.carrier(),
                TrackingStatus.ORDERED,
                firstText(request.recipientPhone(), order.receiverPhone()),
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
                now);
        LogisticsTracking saved = logisticsStore.saveTracking(tracking);
        LogisticsGatewayResult gatewayResult = logisticsGateway.createShipment(saved);
        LogisticsTracking accepted = logisticsStore.saveTracking(saved.withGatewayResult(gatewayResult, now()));
        audit(
                AuditService.LOGISTICS_SHIPMENT_CREATED,
                userId,
                accepted.trackingNo(),
                null,
                "orderId=" + order.id() + ",carrier=" + accepted.carrier() + ",freight=" + accepted.freightAmount());
        return toResponse(accepted);
    }

    private FreightQuote quote(
            com.example.monkey.logistics.domain.LogisticsCarrier carrier,
            String province,
            BigDecimal weightKg,
            int itemCount) {
        return freightCalculator.quote(
                carrier, province, money(weightKg), itemCount, logisticsStore.findFreightTemplates(carrier, province));
    }

    private ParsedAddress addressFor(ShipmentCreateRequestDto request, OrderRecord order) {
        if (StringUtils.hasText(request.addressText())) {
            return addressParser.parse(request.addressText());
        }
        String detail = firstText(request.detail(), order.addressSnapshot());
        if (StringUtils.hasText(request.province()) && StringUtils.hasText(request.city())) {
            return new ParsedAddress(
                    request.province().trim(), request.city().trim(), trim(request.district()), detail);
        }
        return addressParser.parse(detail);
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
        auditService.record(
                eventType,
                AuditService.OUTCOME_SUCCESS,
                actorUserId,
                actorUserId == null ? SYSTEM_ROLE : CUSTOMER_ROLE,
                subject,
                sourceIp,
                detail);
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
        return hmacSha256Hex(
                secret,
                String.join(
                        ":",
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

    private static String firstText(String preferred, String fallback) {
        return StringUtils.hasText(preferred) ? preferred.trim() : trim(fallback);
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static String canonical(String value) {
        return value == null ? "" : value.trim();
    }
}
