package com.example.monkey.payment.application;

import static com.example.monkey.shared.application.security.AuthenticatedPrincipals.requireUserId;

import com.example.monkey.order.domain.OrderStore;
import com.example.monkey.order.domain.OrderStore.OrderRecord;
import com.example.monkey.payment.application.dto.PaymentCallbackRequestDto;
import com.example.monkey.payment.application.dto.PaymentCreateRequestDto;
import com.example.monkey.payment.application.dto.PaymentReconciliationRequestDto;
import com.example.monkey.payment.application.dto.PaymentReconciliationResponseDto;
import com.example.monkey.payment.application.dto.PaymentRefundRequestDto;
import com.example.monkey.payment.application.dto.PaymentRefundResponseDto;
import com.example.monkey.payment.application.dto.PaymentResponseDto;
import com.example.monkey.payment.application.dto.ReconciliationLineDto;
import com.example.monkey.payment.domain.PaymentCallbackReplayGuard;
import com.example.monkey.payment.domain.PaymentEvent;
import com.example.monkey.payment.domain.PaymentFailureClassification;
import com.example.monkey.payment.domain.PaymentGateway;
import com.example.monkey.payment.domain.PaymentGatewayException;
import com.example.monkey.payment.domain.PaymentGatewayResult;
import com.example.monkey.payment.domain.PaymentLedgerEntry;
import com.example.monkey.payment.domain.PaymentLedgerStatus;
import com.example.monkey.payment.domain.PaymentLedgerType;
import com.example.monkey.payment.domain.PaymentMethod;
import com.example.monkey.payment.domain.PaymentOperationAttempt;
import com.example.monkey.payment.domain.PaymentOperationState;
import com.example.monkey.payment.domain.PaymentOrder;
import com.example.monkey.payment.domain.PaymentReconciliationReport;
import com.example.monkey.payment.domain.PaymentRecoveryTenantSource;
import com.example.monkey.payment.domain.PaymentRequestFingerprint;
import com.example.monkey.payment.domain.PaymentResponseSnapshot;
import com.example.monkey.payment.domain.PaymentStatus;
import com.example.monkey.payment.domain.PaymentStore;
import com.example.monkey.payment.domain.PaymentTransitionResolver;
import com.example.monkey.payment.domain.ReconciliationLine;
import com.example.monkey.payment.domain.ReconciliationStatus;
import com.example.monkey.payment.domain.RefundAuditIntent;
import com.example.monkey.payment.domain.RefundAuditState;
import com.example.monkey.payment.domain.RefundResponseSnapshot;
import com.example.monkey.shared.application.observability.AuditService;
import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.application.tenant.TenantContext;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.shared.domain.id.IdGenerator;
import com.example.monkey.user.domain.UserAccountStore;
import com.example.monkey.user.domain.UserAccountStore.UserAccount;
import com.example.monkey.user.domain.UserMfaVerifier;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class PaymentApplicationService {

    private static final Logger log = LoggerFactory.getLogger(PaymentApplicationService.class);
    private static final int QUERY_BATCH_SIZE = 100;
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;
    private static final Pattern IDEMPOTENCY_KEY_PATTERN = Pattern.compile("[A-Za-z0-9._:-]+");
    private static final List<Pattern> CONSTRAINT_TOKEN_PATTERNS = List.of(
            Pattern.compile("(?i)\\bfor\\s+key\\s+'([^']+)'"),
            Pattern.compile("(?i)\\bfor\\s+key\\s+`([^`]+)`"),
            Pattern.compile("(?i)\\bfor\\s+key\\s+\"([^\"]+)\""),
            Pattern.compile("(?i)\\bconstraint\\s*\\[\\s*([^\\]]+?)\\s*]"));
    private static final String CUSTOMER_ROLE = "CUSTOMER";
    private static final String SYSTEM_ROLE = "SYSTEM";
    private static final BigDecimal DEFAULT_HIGH_VALUE_THRESHOLD = BigDecimal.valueOf(5000);
    private static final Set<String> PAYMENT_RESERVATION_CONSTRAINTS =
            Set.of("uk_payment_order_user_key", "uk_payment_order_active_order");
    private static final Set<String> REFUND_RESERVATION_CONSTRAINTS = Set.of("uk_payment_ledger_request");

    private final PaymentStore paymentStore;
    private final PaymentGateway paymentGateway;
    private final PaymentCallbackReplayGuard callbackReplayGuard;
    private final PaymentTransitionResolver transitionResolver;
    private final OrderStore orderStore;
    private final UserAccountStore userAccountStore;
    private final UserMfaVerifier userMfaVerifier;
    private final IdGenerator idGenerator;
    private final AuditService auditService;
    private final PaymentTransactions paymentTransactions;
    private final PaymentRecoveryTenantSource recoveryTenantSource;
    private final Clock clock;
    private final Duration callbackTtl;
    private final Duration queryAfter;
    private final Duration operationLease;
    private final Duration retryAfter;
    private final BigDecimal highValueThreshold;
    private final String callbackSecret;

    @Autowired
    public PaymentApplicationService(
            PaymentStore paymentStore,
            PaymentGateway paymentGateway,
            PaymentCallbackReplayGuard callbackReplayGuard,
            PaymentTransitionResolver transitionResolver,
            OrderStore orderStore,
            UserAccountStore userAccountStore,
            UserMfaVerifier userMfaVerifier,
            IdGenerator idGenerator,
            AuditService auditService,
            PaymentTransactions paymentTransactions,
            PaymentRecoveryTenantSource recoveryTenantSource,
            @Value("${app.payment.callback-ttl:PT24H}") Duration callbackTtl,
            @Value("${app.payment.query-after:PT5M}") Duration queryAfter,
            @Value("${app.payment.operation-lease:PT2M}") Duration operationLease,
            @Value("${app.payment.retry-after:PT30S}") Duration retryAfter,
            @Value("${app.payment.high-value-threshold:5000}") BigDecimal highValueThreshold,
            @Value("${app.payment.callback-secret:}") String callbackSecret) {
        this(
                paymentStore,
                paymentGateway,
                callbackReplayGuard,
                transitionResolver,
                orderStore,
                userAccountStore,
                userMfaVerifier,
                idGenerator,
                auditService,
                paymentTransactions,
                recoveryTenantSource,
                Clock.systemDefaultZone(),
                callbackTtl,
                queryAfter,
                operationLease,
                retryAfter,
                highValueThreshold,
                callbackSecret);
    }

    PaymentApplicationService(
            PaymentStore paymentStore,
            PaymentGateway paymentGateway,
            PaymentCallbackReplayGuard callbackReplayGuard,
            PaymentTransitionResolver transitionResolver,
            OrderStore orderStore,
            UserAccountStore userAccountStore,
            UserMfaVerifier userMfaVerifier,
            IdGenerator idGenerator,
            AuditService auditService,
            PaymentTransactions paymentTransactions,
            Clock clock,
            Duration callbackTtl,
            Duration queryAfter,
            BigDecimal highValueThreshold,
            String callbackSecret) {
        this(
                paymentStore,
                paymentGateway,
                callbackReplayGuard,
                transitionResolver,
                orderStore,
                userAccountStore,
                userMfaVerifier,
                idGenerator,
                auditService,
                paymentTransactions,
                PaymentRecoveryTenantSource.none(),
                clock,
                callbackTtl,
                queryAfter,
                Duration.ofMinutes(2),
                Duration.ofSeconds(30),
                highValueThreshold,
                callbackSecret);
    }

    PaymentApplicationService(
            PaymentStore paymentStore,
            PaymentGateway paymentGateway,
            PaymentCallbackReplayGuard callbackReplayGuard,
            PaymentTransitionResolver transitionResolver,
            OrderStore orderStore,
            UserAccountStore userAccountStore,
            UserMfaVerifier userMfaVerifier,
            IdGenerator idGenerator,
            AuditService auditService,
            PaymentTransactions paymentTransactions,
            PaymentRecoveryTenantSource recoveryTenantSource,
            Clock clock,
            Duration callbackTtl,
            Duration queryAfter,
            BigDecimal highValueThreshold,
            String callbackSecret) {
        this(
                paymentStore,
                paymentGateway,
                callbackReplayGuard,
                transitionResolver,
                orderStore,
                userAccountStore,
                userMfaVerifier,
                idGenerator,
                auditService,
                paymentTransactions,
                recoveryTenantSource,
                clock,
                callbackTtl,
                queryAfter,
                Duration.ofMinutes(2),
                Duration.ofSeconds(30),
                highValueThreshold,
                callbackSecret);
    }

    PaymentApplicationService(
            PaymentStore paymentStore,
            PaymentGateway paymentGateway,
            PaymentCallbackReplayGuard callbackReplayGuard,
            PaymentTransitionResolver transitionResolver,
            OrderStore orderStore,
            UserAccountStore userAccountStore,
            UserMfaVerifier userMfaVerifier,
            IdGenerator idGenerator,
            AuditService auditService,
            PaymentTransactions paymentTransactions,
            Clock clock,
            Duration callbackTtl,
            Duration queryAfter,
            Duration operationLease,
            Duration retryAfter,
            BigDecimal highValueThreshold,
            String callbackSecret) {
        this(
                paymentStore,
                paymentGateway,
                callbackReplayGuard,
                transitionResolver,
                orderStore,
                userAccountStore,
                userMfaVerifier,
                idGenerator,
                auditService,
                paymentTransactions,
                PaymentRecoveryTenantSource.none(),
                clock,
                callbackTtl,
                queryAfter,
                operationLease,
                retryAfter,
                highValueThreshold,
                callbackSecret);
    }

    PaymentApplicationService(
            PaymentStore paymentStore,
            PaymentGateway paymentGateway,
            PaymentCallbackReplayGuard callbackReplayGuard,
            PaymentTransitionResolver transitionResolver,
            OrderStore orderStore,
            UserAccountStore userAccountStore,
            UserMfaVerifier userMfaVerifier,
            IdGenerator idGenerator,
            AuditService auditService,
            PaymentTransactions paymentTransactions,
            PaymentRecoveryTenantSource recoveryTenantSource,
            Clock clock,
            Duration callbackTtl,
            Duration queryAfter,
            Duration operationLease,
            Duration retryAfter,
            BigDecimal highValueThreshold,
            String callbackSecret) {
        this.paymentStore = paymentStore;
        this.paymentGateway = paymentGateway;
        this.callbackReplayGuard = callbackReplayGuard;
        this.transitionResolver = transitionResolver;
        this.orderStore = orderStore;
        this.userAccountStore = userAccountStore;
        this.userMfaVerifier = userMfaVerifier;
        this.idGenerator = idGenerator;
        this.auditService = auditService;
        this.paymentTransactions = paymentTransactions;
        this.recoveryTenantSource = recoveryTenantSource;
        this.clock = clock;
        this.callbackTtl = callbackTtl == null ? Duration.ofHours(24) : callbackTtl;
        this.queryAfter = queryAfter == null ? Duration.ofMinutes(5) : queryAfter;
        this.operationLease = requirePositiveDuration(operationLease, Duration.ofMinutes(2), "operationLease");
        this.retryAfter = requirePositiveDuration(retryAfter, Duration.ofSeconds(30), "retryAfter");
        this.highValueThreshold = highValueThreshold == null ? DEFAULT_HIGH_VALUE_THRESHOLD : highValueThreshold;
        this.callbackSecret = requireCallbackSecret(callbackSecret);
    }

    @WithSpan("payment.create")
    public PaymentResponseDto createPayment(
            SessionUser currentUser, PaymentCreateRequestDto request, String idempotencyKey) {
        Long userId = requireUserId(currentUser);
        String key = normalizeIdempotencyKey(idempotencyKey);
        OrderRecord order = orderStore
                .findVisibleByIdAndUserId(request.orderId(), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN, "Order is not available for payment"));
        BigDecimal amount = money(order.price());
        PaymentRequestFingerprint fingerprint =
                PaymentRequestFingerprint.of(order.id(), request.method(), amount, "CNY");
        PaymentStore.PaymentIntent existing = paymentTransactions.execute(
                () -> paymentStore.findByUserIdAndIdempotencyKey(userId, key).orElse(null));
        PaymentReservation reservation;
        if (existing == null) {
            requireMfaForHighValue(userId, amount, request.totpCode());
            String cardNo = normalizedCardNo(request.method(), request.bankCardNo());
            reservation = reservePayment(userId, request, key, order, amount, cardNo, fingerprint);
        } else {
            reservation = PaymentReservation.requiresClaim(existing);
        }
        PaymentStore.PaymentIntent executionIntent = reservation.ownedByCaller()
                ? reservation.intent()
                : claimPaymentForExecution(reservation.intent(), fingerprint);
        if (PaymentOperationState.COMPLETED.equals(executionIntent.operationState())) {
            return PaymentDtoAssembler.toResponse(executionIntent.payment(), executionIntent.responseSnapshot());
        }
        if (PaymentOperationState.TERMINAL_FAILED.equals(executionIntent.operationState())) {
            throw PaymentGatewayException.terminalReplay(
                    executionIntent.operation().terminalFailureCode());
        }
        PaymentCompletion completion = executePaymentGateway(executionIntent, userId, key, fingerprint);
        if (completion.completedNow()) {
            audit(
                    AuditService.PAYMENT_CREATED,
                    userId,
                    completion.intent().payment().paymentNo(),
                    null,
                    "orderId=" + order.id() + ",method=" + request.method());
        }
        return PaymentDtoAssembler.toResponse(
                completion.intent().payment(), completion.intent().responseSnapshot());
    }

    @WithSpan("payment.callback")
    @Transactional
    public PaymentResponseDto handleCallback(PaymentCallbackRequestDto request, String sourceIp) {
        verifySignature(request);
        PaymentOrder payment = requirePayment(request.paymentNo());
        if (!callbackReplayGuard.reserve(request.provider(), request.paymentNo(), request.callbackId(), callbackTtl)) {
            return PaymentDtoAssembler.toResponse(payment);
        }
        if (PaymentStatus.PAID.equals(payment.status())) {
            return PaymentDtoAssembler.toResponse(payment);
        }
        assertAmountMatches(payment.amount(), request.amount());
        PaymentOrder updated = "SUCCESS".equalsIgnoreCase(request.status())
                ? confirmPayment(payment, request.providerTradeNo(), request.callbackId())
                : failPayment(payment, request.callbackId());
        audit(
                AuditService.PAYMENT_CALLBACK_ACCEPTED,
                updated.userId(),
                updated.paymentNo(),
                sourceIp,
                "status=" + request.status() + ",callbackId=" + request.callbackId());
        return PaymentDtoAssembler.toResponse(updated);
    }

    @WithSpan("payment.refund")
    public PaymentRefundResponseDto refund(
            SessionUser currentUser, PaymentRefundRequestDto request, String idempotencyKey) {
        Long userId = requireUserId(currentUser);
        String key = normalizeIdempotencyKey(idempotencyKey);
        return refund(
                request,
                key,
                payment -> requireOwnedPayment(payment, userId),
                new RefundAuditContext(AuditService.PAYMENT_REFUNDED, userId, CUSTOMER_ROLE, null, false));
    }

    @WithSpan("payment.find")
    @Transactional(readOnly = true)
    public PaymentResponseDto findByOrder(SessionUser currentUser, Long orderId) {
        Long userId = requireUserId(currentUser);
        return paymentStore
                .findByOrderIdAndUserId(orderId, userId)
                .map(PaymentDtoAssembler::toResponse)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Payment order does not exist"));
    }

    @WithSpan("payment.admin.find")
    @Transactional
    public PaymentResponseDto findByOrderAsAdmin(SessionUser currentUser, Long orderId, String sourceIp) {
        Long adminUserId = requireAdminUserId(currentUser);
        OrderRecord order = requireOrder(orderId);
        PaymentOrder payment = paymentStore
                .findByOrderIdAndUserId(orderId, order.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Payment order does not exist"));
        auditService.recordReliable(
                AuditService.PAYMENT_ADMIN_READ,
                AuditService.OUTCOME_SUCCESS,
                adminUserId,
                "ADMIN",
                payment.paymentNo(),
                sourceIp,
                "orderId=" + orderId + ",ownerUserId=" + payment.userId());
        return PaymentDtoAssembler.toResponse(payment);
    }

    @WithSpan("payment.admin.refund")
    public PaymentRefundResponseDto refundAsAdmin(
            SessionUser currentUser, PaymentRefundRequestDto request, String idempotencyKey, String sourceIp) {
        Long adminUserId = requireAdminUserId(currentUser);
        String key = normalizeIdempotencyKey(idempotencyKey);
        return refund(
                request,
                key,
                this::requireMatchingOrderOwner,
                new RefundAuditContext(AuditService.PAYMENT_ADMIN_REFUNDED, adminUserId, "ADMIN", sourceIp, true));
    }

    @WithSpan("payment.reconciliation")
    @Transactional
    public PaymentReconciliationResponseDto reconcile(PaymentReconciliationRequestDto request) {
        PaymentReconciliationReport report = reconcileInternal(
                request.provider(),
                request.reportDate(),
                request.lines().stream().map(PaymentApplicationService::toLine).toList());
        return PaymentDtoAssembler.toResponse(report);
    }

    @Scheduled(fixedDelayString = "${app.payment.query-delay:PT1M}")
    @SchedulerLock(name = "payment-query-timeout-orders", lockAtMostFor = "${app.payment.query-lock-at-most-for:PT10M}")
    @Transactional
    public void queryTimedOutPaymentsScheduled() {
        queryTimedOutPayments();
    }

    @Scheduled(fixedDelayString = "${app.payment.recovery-delay:PT30S}")
    @SchedulerLock(
            name = "payment-recover-expired-operations",
            lockAtMostFor = "${app.payment.recovery-lock-at-most-for:PT5M}")
    public void recoverExpiredOperationsScheduled() {
        LocalDateTime recoveryTime = now();
        for (Long tenantId : recoveryTenantSource.findTenantIdsReadyForRecovery(recoveryTime, QUERY_BATCH_SIZE)) {
            if (tenantId == null || tenantId <= 0) {
                continue;
            }
            Long previousTenantId = TenantContext.currentTenantId().orElse(null);
            try {
                TenantContext.setTenantId(tenantId);
                recoverExpiredOperations(recoveryTime);
            } catch (RuntimeException exception) {
                log.error("Payment recovery failed for tenant {}", tenantId, exception);
            } finally {
                if (previousTenantId == null) {
                    TenantContext.clear();
                } else {
                    TenantContext.setTenantId(previousTenantId);
                }
            }
        }
    }

    public int recoverExpiredOperations() {
        return recoverExpiredOperations(now());
    }

    private int recoverExpiredOperations(LocalDateTime recoveryTime) {
        int handled = 0;
        for (PaymentStore.PaymentIntent candidate :
                paymentStore.findExpiredPaymentOperations(recoveryTime, QUERY_BATCH_SIZE)) {
            PaymentStore.PaymentIntent claimed = claimExpiredPayment(candidate, recoveryTime);
            if (claimed == null) {
                continue;
            }
            handled++;
            PaymentRequestFingerprint fingerprint = new PaymentRequestFingerprint(claimed.requestFingerprint());
            try {
                executePaymentGateway(
                        claimed, claimed.payment().userId(), claimed.payment().idempotencyKey(), fingerprint);
            } catch (RuntimeException ignored) {
                // The operation now carries a retryable or terminal classification.
            }
        }
        for (PaymentStore.RefundRequest candidate :
                paymentStore.findExpiredRefundOperations(recoveryTime, QUERY_BATCH_SIZE)) {
            PaymentOrder payment =
                    paymentStore.findById(candidate.ledger().paymentId()).orElse(null);
            if (payment == null) {
                continue;
            }
            RefundReservation claimed = claimExpiredRefund(payment, candidate, recoveryTime);
            if (claimed == null) {
                continue;
            }
            handled++;
            try {
                RefundCompletion completion =
                        executeRefundGateway(claimed, claimed.refund().ledger().requestKey(), ignored -> {});
                dispatchRefundAudit(
                        completion.payment().paymentNo(),
                        completion.refund().ledger().requestKey());
            } catch (RuntimeException ignored) {
                // The operation now carries a retryable or terminal classification.
            }
        }
        for (PaymentStore.RefundRequest refund : paymentStore.findPendingRefundAudits(QUERY_BATCH_SIZE)) {
            paymentStore
                    .findById(refund.ledger().paymentId())
                    .ifPresent(payment -> dispatchRefundAudit(
                            payment.paymentNo(), refund.ledger().requestKey()));
        }
        return handled;
    }

    private PaymentStore.PaymentIntent claimExpiredPayment(
            PaymentStore.PaymentIntent candidate, LocalDateTime recoveryTime) {
        return paymentTransactions.execute(() -> paymentStore
                .withLockedPayment(candidate.payment().paymentNo(), ignored -> {
                    PaymentStore.PaymentIntent latest = paymentStore
                            .findByUserIdAndIdempotencyKey(
                                    candidate.payment().userId(),
                                    candidate.payment().idempotencyKey())
                            .orElse(null);
                    if (latest == null || !latest.operation().isExpired(recoveryTime)) {
                        return null;
                    }
                    return paymentStore.savePayment(
                            latest.payment(),
                            latest.requestFingerprint(),
                            latest.operation().claim(recoveryTime, operationLease),
                            latest.merchantToken(),
                            latest.responseSnapshot());
                })
                .orElse(null));
    }

    private RefundReservation claimExpiredRefund(
            PaymentOrder candidatePayment, PaymentStore.RefundRequest candidate, LocalDateTime recoveryTime) {
        return paymentTransactions.execute(() -> paymentStore
                .withLockedPayment(candidatePayment.paymentNo(), payment -> {
                    PaymentStore.RefundRequest latest = paymentStore
                            .findRefundRequest(payment.id(), candidate.ledger().requestKey())
                            .orElse(null);
                    if (latest == null || !latest.operation().isExpired(recoveryTime)) {
                        return null;
                    }
                    PaymentStore.RefundRequest claimed = paymentStore.saveLedger(
                            latest.ledger(),
                            latest.requestFingerprint(),
                            latest.operation().claim(recoveryTime, operationLease),
                            latest.merchantToken(),
                            latest.responseSnapshot(),
                            latest.auditIntent());
                    return new RefundReservation(
                            payment, claimed, new PaymentRequestFingerprint(claimed.requestFingerprint()));
                })
                .orElse(null));
    }

    public int queryTimedOutPayments() {
        int handled = 0;
        LocalDateTime cutoff = now().minus(queryAfter);
        for (PaymentOrder payment : paymentStore.findPendingCreatedBefore(cutoff, QUERY_BATCH_SIZE)) {
            PaymentGatewayResult result = paymentGateway.query(payment);
            if (PaymentStatus.PAID.equals(result.status())) {
                paymentTransactions.execute(
                        () -> confirmPayment(payment, result.providerTradeNo(), "query:" + payment.paymentNo()));
                handled++;
            } else if (PaymentStatus.FAILED.equals(result.status())) {
                paymentTransactions.execute(() -> failPayment(payment, "query:" + payment.paymentNo()));
                handled++;
            }
        }
        return handled;
    }

    @Scheduled(cron = "${app.payment.reconciliation-cron:0 35 3 * * *}")
    @SchedulerLock(
            name = "payment-daily-reconciliation",
            lockAtMostFor = "${app.payment.reconciliation-lock-at-most-for:PT30M}")
    @Transactional
    public PaymentReconciliationResponseDto reconcileYesterday() {
        return PaymentDtoAssembler.toResponse(
                reconcileInternal(PaymentMethod.WECHAT, LocalDate.now(clock).minusDays(1), List.of()));
    }

    private PaymentReservation reservePayment(
            Long userId,
            PaymentCreateRequestDto request,
            String key,
            OrderRecord order,
            BigDecimal amount,
            String cardNo,
            PaymentRequestFingerprint fingerprint) {
        try {
            return paymentTransactions.execute(() -> {
                PaymentStore.PaymentIntent existing =
                        paymentStore.findByUserIdAndIdempotencyKey(userId, key).orElse(null);
                if (existing != null) {
                    return PaymentReservation.requiresClaim(existing);
                }
                if (paymentStore.findActiveByOrderId(order.id()).isPresent()) {
                    throw paymentConflict("Order already has an active payment intent");
                }
                Long id = idGenerator.nextId();
                LocalDateTime createdAt = now();
                PaymentOrder payment = new PaymentOrder(
                        id,
                        "PAY" + id,
                        order.id(),
                        userId,
                        request.method(),
                        amount,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        PaymentStatus.PENDING,
                        key,
                        null,
                        cardNo,
                        last4(cardNo),
                        null,
                        null,
                        createdAt,
                        createdAt);
                return PaymentReservation.owned(paymentStore.savePayment(
                        payment,
                        fingerprint.value(),
                        PaymentOperationAttempt.initial(createdAt, operationLease),
                        payment.paymentNo(),
                        null));
            });
        } catch (DataIntegrityViolationException exception) {
            if (!causedByConstraint(exception, PAYMENT_RESERVATION_CONSTRAINTS)) {
                throw exception;
            }
            return paymentTransactions.execute(() -> findPaymentReservationWinner(userId, key, order.id()));
        }
    }

    private PaymentReservation findPaymentReservationWinner(Long userId, String key, Long orderId) {
        PaymentStore.PaymentIntent winner =
                paymentStore.findByUserIdAndIdempotencyKey(userId, key).orElse(null);
        if (winner != null) {
            return PaymentReservation.requiresClaim(winner);
        }
        if (paymentStore.findActiveByOrderId(orderId).isPresent()) {
            throw paymentConflict("Order already has an active payment intent");
        }
        throw paymentConflict("Payment intent reservation conflicts with an existing request");
    }

    private PaymentStore.PaymentIntent classifyPaymentIntent(
            PaymentStore.PaymentIntent intent, PaymentRequestFingerprint fingerprint) {
        if (PaymentOperationState.LEGACY_UNREPLAYABLE.equals(intent.operationState())) {
            throw paymentConflict("Legacy payment response cannot be replayed safely");
        }
        requireMatchingFingerprint(intent.requestFingerprint(), fingerprint);
        if (!StringUtils.hasText(intent.merchantToken())) {
            throw paymentConflict("Payment intent is missing its merchant idempotency token");
        }
        if (PaymentOperationState.COMPLETED.equals(intent.operationState()) && intent.responseSnapshot() == null) {
            throw paymentConflict("Payment response snapshot is unavailable");
        }
        if (!PaymentOperationState.RESERVED.equals(intent.operationState())
                && !PaymentOperationState.RETRYABLE.equals(intent.operationState())
                && !PaymentOperationState.COMPLETED.equals(intent.operationState())
                && !PaymentOperationState.TERMINAL_FAILED.equals(intent.operationState())) {
            throw paymentConflict("Payment intent has an invalid idempotency state");
        }
        return intent;
    }

    private PaymentStore.PaymentIntent claimPaymentForExecution(
            PaymentStore.PaymentIntent existing, PaymentRequestFingerprint fingerprint) {
        PaymentStore.PaymentIntent classified = classifyPaymentIntent(existing, fingerprint);
        if (PaymentOperationState.COMPLETED.equals(classified.operationState())
                || PaymentOperationState.TERMINAL_FAILED.equals(classified.operationState())) {
            return classified;
        }
        LocalDateTime claimTime = now();
        return paymentTransactions.execute(() -> paymentStore
                .withLockedPayment(existing.payment().paymentNo(), ignored -> {
                    PaymentStore.PaymentIntent latest = paymentStore
                            .findByUserIdAndIdempotencyKey(
                                    existing.payment().userId(),
                                    existing.payment().idempotencyKey())
                            .map(intent -> classifyPaymentIntent(intent, fingerprint))
                            .filter(intent -> intent.payment()
                                    .paymentNo()
                                    .equals(existing.payment().paymentNo()))
                            .orElseThrow(() -> paymentConflict("Payment intent reservation is missing"));
                    if (PaymentOperationState.COMPLETED.equals(latest.operationState())
                            || PaymentOperationState.TERMINAL_FAILED.equals(latest.operationState())) {
                        return latest;
                    }
                    if (!latest.operation().isClaimableAt(claimTime)) {
                        throw paymentConflict("Payment operation is already in progress");
                    }
                    return paymentStore.savePayment(
                            latest.payment(),
                            latest.requestFingerprint(),
                            latest.operation().claim(claimTime, operationLease),
                            latest.merchantToken(),
                            latest.responseSnapshot());
                })
                .orElseThrow(() -> paymentConflict("Payment intent reservation is missing")));
    }

    private PaymentCompletion completePayment(
            String paymentNo,
            Long userId,
            String key,
            PaymentRequestFingerprint fingerprint,
            int expectedAttempt,
            PaymentGatewayResult gatewayResult) {
        return paymentTransactions.execute(() -> paymentStore
                .withLockedPayment(paymentNo, ignored -> {
                    PaymentStore.PaymentIntent latest = paymentStore
                            .findByUserIdAndIdempotencyKey(userId, key)
                            .map(intent -> classifyPaymentIntent(intent, fingerprint))
                            .filter(intent -> intent.payment().paymentNo().equals(paymentNo))
                            .orElseThrow(() -> paymentConflict("Payment intent reservation is missing"));
                    if (PaymentOperationState.COMPLETED.equals(latest.operationState())) {
                        return new PaymentCompletion(latest, false);
                    }
                    if (PaymentOperationState.TERMINAL_FAILED.equals(latest.operationState())) {
                        throw PaymentGatewayException.terminalReplay(
                                latest.operation().terminalFailureCode());
                    }
                    if (latest.operation().attemptCount() != expectedAttempt) {
                        throw paymentConflict("Payment operation attempt is stale");
                    }
                    PaymentOrder completed =
                            latest.payment().withProviderTradeNo(gatewayResult.providerTradeNo(), now());
                    PaymentResponseSnapshot responseSnapshot =
                            PaymentResponseSnapshot.capture(completed, gatewayResult.paymentUrl());
                    PaymentStore.PaymentIntent saved = paymentStore.savePayment(
                            completed,
                            fingerprint.value(),
                            latest.operation().completed(),
                            latest.merchantToken(),
                            responseSnapshot);
                    return new PaymentCompletion(saved, true);
                })
                .orElseThrow(() -> paymentConflict("Payment intent reservation is missing")));
    }

    private PaymentCompletion executePaymentGateway(
            PaymentStore.PaymentIntent reservation, Long userId, String key, PaymentRequestFingerprint fingerprint) {
        PaymentGatewayResult gatewayResult;
        try {
            gatewayResult = paymentGateway.create(reservation.payment(), reservation.merchantToken());
        } catch (PaymentGatewayException exception) {
            String terminalFailureCode = exception.isTerminal() ? exception.providerCode() : null;
            recordPaymentGatewayFailure(reservation, exception.classification(), terminalFailureCode);
            throw exception.isTerminal() ? PaymentGatewayException.terminalReplay(terminalFailureCode) : exception;
        } catch (RuntimeException exception) {
            recordPaymentGatewayFailure(reservation, PaymentFailureClassification.UNKNOWN, null);
            throw exception;
        }
        if (PaymentStatus.FAILED.equals(gatewayResult.status())) {
            PaymentGatewayException rejected =
                    PaymentGatewayException.rejected("Payment provider rejected the request");
            recordPaymentGatewayFailure(reservation, rejected.classification(), rejected.providerCode());
            throw rejected;
        }
        try {
            return completePayment(
                    reservation.payment().paymentNo(),
                    userId,
                    key,
                    fingerprint,
                    reservation.operation().attemptCount(),
                    gatewayResult);
        } catch (RuntimeException exception) {
            recordPaymentGatewayFailure(reservation, PaymentFailureClassification.LOCAL_COMPLETION, null);
            throw exception;
        }
    }

    private void recordPaymentGatewayFailure(
            PaymentStore.PaymentIntent reservation,
            PaymentFailureClassification classification,
            String terminalFailureCode) {
        paymentTransactions.execute(() -> paymentStore
                .withLockedPayment(reservation.payment().paymentNo(), ignored -> {
                    PaymentStore.PaymentIntent latest = paymentStore
                            .findByUserIdAndIdempotencyKey(
                                    reservation.payment().userId(),
                                    reservation.payment().idempotencyKey())
                            .orElse(null);
                    if (latest == null
                            || !latest.payment()
                                    .paymentNo()
                                    .equals(reservation.payment().paymentNo())
                            || latest.operation().attemptCount()
                                    != reservation.operation().attemptCount()) {
                        return null;
                    }
                    if (!latest.operation().isRecoverable()) {
                        return latest;
                    }
                    boolean terminal = terminalFailureCode != null;
                    PaymentOperationAttempt operation = terminal
                            ? latest.operation().terminal(classification, terminalFailureCode)
                            : latest.operation().retryable(classification, now().plus(retryAfter));
                    PaymentOrder payment = terminal
                                    && PaymentStatus.PENDING.equals(
                                            latest.payment().status())
                            ? latest.payment().fail(now())
                            : latest.payment();
                    return paymentStore.savePayment(
                            payment,
                            latest.requestFingerprint(),
                            operation,
                            latest.merchantToken(),
                            latest.responseSnapshot());
                })
                .orElse(null));
    }

    private PaymentRefundResponseDto refund(
            PaymentRefundRequestDto request,
            String key,
            Consumer<PaymentOrder> accessCheck,
            RefundAuditContext auditContext) {
        RefundReservation reservation = reserveRefund(request, key, accessCheck, auditContext);
        if (PaymentOperationState.COMPLETED.equals(reservation.refund().operationState())) {
            dispatchRefundAudit(reservation.payment().paymentNo(), key);
            return PaymentDtoAssembler.toRefundResponse(
                    reservation.payment(),
                    reservation.refund().ledger(),
                    reservation.refund().responseSnapshot());
        }
        if (PaymentOperationState.TERMINAL_FAILED.equals(reservation.refund().operationState())) {
            throw PaymentGatewayException.terminalReplay(
                    reservation.refund().operation().terminalFailureCode());
        }
        RefundCompletion completion = executeRefundGateway(reservation, key, accessCheck);
        dispatchRefundAudit(completion.payment().paymentNo(), key);
        return PaymentDtoAssembler.toRefundResponse(
                completion.payment(),
                completion.refund().ledger(),
                completion.refund().responseSnapshot());
    }

    private RefundReservation reserveRefund(
            PaymentRefundRequestDto request,
            String key,
            Consumer<PaymentOrder> accessCheck,
            RefundAuditContext auditContext) {
        try {
            return paymentTransactions.execute(() -> paymentStore
                    .withLockedPayment(
                            request.paymentNo(),
                            payment -> reserveRefund(payment, request, key, accessCheck, auditContext))
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Payment order does not exist")));
        } catch (DataIntegrityViolationException exception) {
            if (!causedByConstraint(exception, REFUND_RESERVATION_CONSTRAINTS)) {
                throw exception;
            }
            return paymentTransactions.execute(() -> paymentStore
                    .withLockedPayment(
                            request.paymentNo(),
                            payment -> classifyRefundReservationWinner(payment, request, key, accessCheck))
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Payment order does not exist")));
        }
    }

    private RefundReservation reserveRefund(
            PaymentOrder payment,
            PaymentRefundRequestDto request,
            String key,
            Consumer<PaymentOrder> accessCheck,
            RefundAuditContext auditContext) {
        accessCheck.accept(payment);
        PaymentRequestFingerprint fingerprint =
                PaymentRequestFingerprint.ofRefund(payment.id(), request.amount(), request.reason());
        PaymentStore.RefundRequest existing =
                paymentStore.findRefundRequest(payment.id(), key).orElse(null);
        if (existing != null) {
            return new RefundReservation(payment, claimRefundForExecution(existing, fingerprint), fingerprint);
        }
        BigDecimal amount = money(request.amount());
        BigDecimal reservedAmount = money(paymentStore.sumAcceptedRefundAmount(payment.id()));
        BigDecimal availableAmount = money(payment.refundableAmount().subtract(reservedAmount));
        if (amount.compareTo(BigDecimal.ZERO) <= 0 || amount.compareTo(availableAmount) > 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Refund amount exceeds refundable amount");
        }
        Long ledgerId = idGenerator.nextId();
        PaymentLedgerEntry ledger = new PaymentLedgerEntry(
                ledgerId,
                payment.id(),
                payment.orderId(),
                payment.userId(),
                PaymentLedgerType.REFUND,
                amount,
                PaymentLedgerStatus.ACCEPTED,
                key,
                null,
                now());
        String merchantToken = payment.paymentNo() + ":refund:" + ledgerId;
        PaymentStore.RefundRequest saved = paymentStore.saveLedger(
                ledger,
                fingerprint.value(),
                PaymentOperationAttempt.initial(now(), operationLease),
                merchantToken,
                null,
                RefundAuditIntent.waiting(
                        auditContext.eventType(),
                        auditContext.actorUserId(),
                        auditContext.actorRole(),
                        auditContext.sourceIp(),
                        auditContext.includeOwner()));
        return new RefundReservation(payment, saved, fingerprint);
    }

    private RefundReservation classifyRefundReservationWinner(
            PaymentOrder payment, PaymentRefundRequestDto request, String key, Consumer<PaymentOrder> accessCheck) {
        accessCheck.accept(payment);
        PaymentRequestFingerprint fingerprint =
                PaymentRequestFingerprint.ofRefund(payment.id(), request.amount(), request.reason());
        PaymentStore.RefundRequest winner = paymentStore
                .findRefundRequest(payment.id(), key)
                .orElseThrow(() -> paymentConflict("Refund reservation conflicts with an existing request"));
        return new RefundReservation(payment, claimRefundForExecution(winner, fingerprint), fingerprint);
    }

    private PaymentStore.RefundRequest classifyRefundRequest(
            PaymentStore.RefundRequest refund, PaymentRequestFingerprint fingerprint) {
        if (PaymentOperationState.LEGACY_UNREPLAYABLE.equals(refund.operationState())) {
            throw paymentConflict("Legacy refund response cannot be replayed safely");
        }
        requireMatchingFingerprint(refund.requestFingerprint(), fingerprint);
        if (!StringUtils.hasText(refund.merchantToken())) {
            throw paymentConflict("Refund reservation is missing its merchant idempotency token");
        }
        if (PaymentOperationState.COMPLETED.equals(refund.operationState()) && refund.responseSnapshot() == null) {
            throw paymentConflict("Refund response snapshot is unavailable");
        }
        if (!PaymentOperationState.RESERVED.equals(refund.operationState())
                && !PaymentOperationState.RETRYABLE.equals(refund.operationState())
                && !PaymentOperationState.COMPLETED.equals(refund.operationState())
                && !PaymentOperationState.TERMINAL_FAILED.equals(refund.operationState())) {
            throw paymentConflict("Refund reservation has an invalid idempotency state");
        }
        return refund;
    }

    private PaymentStore.RefundRequest claimRefundForExecution(
            PaymentStore.RefundRequest refund, PaymentRequestFingerprint fingerprint) {
        PaymentStore.RefundRequest classified = classifyRefundRequest(refund, fingerprint);
        if (PaymentOperationState.COMPLETED.equals(classified.operationState())
                || PaymentOperationState.TERMINAL_FAILED.equals(classified.operationState())) {
            return classified;
        }
        LocalDateTime claimTime = now();
        if (!classified.operation().isClaimableAt(claimTime)) {
            throw paymentConflict("Refund operation is already in progress");
        }
        return paymentStore.saveLedger(
                classified.ledger(),
                classified.requestFingerprint(),
                classified.operation().claim(claimTime, operationLease),
                classified.merchantToken(),
                classified.responseSnapshot(),
                classified.auditIntent());
    }

    private RefundCompletion completeRefund(
            String paymentNo,
            String key,
            PaymentRequestFingerprint fingerprint,
            int expectedAttempt,
            PaymentGatewayResult gatewayResult,
            Consumer<PaymentOrder> accessCheck) {
        return paymentTransactions.execute(() -> paymentStore
                .withLockedPayment(paymentNo, payment -> {
                    accessCheck.accept(payment);
                    PaymentStore.RefundRequest reserved = paymentStore
                            .findRefundRequest(payment.id(), key)
                            .map(refund -> classifyRefundRequest(refund, fingerprint))
                            .orElseThrow(() -> paymentConflict("Refund reservation is missing"));
                    if (PaymentOperationState.COMPLETED.equals(reserved.operationState())) {
                        return new RefundCompletion(payment, reserved, false);
                    }
                    if (PaymentOperationState.TERMINAL_FAILED.equals(reserved.operationState())) {
                        throw PaymentGatewayException.terminalReplay(
                                reserved.operation().terminalFailureCode());
                    }
                    if (reserved.operation().attemptCount() != expectedAttempt) {
                        throw paymentConflict("Refund operation attempt is stale");
                    }
                    BigDecimal amount = reserved.ledger().amount();
                    if (amount.compareTo(payment.refundableAmount()) > 0) {
                        throw paymentConflict("Reserved refund exceeds the remaining refundable amount");
                    }
                    PaymentEvent event = amount.compareTo(payment.refundableAmount()) == 0
                            ? PaymentEvent.REFUND_ALL
                            : PaymentEvent.REFUND_PARTIAL;
                    PaymentStatus nextStatus = transitionResolver.nextStatus(payment.status(), event);
                    PaymentOrder updated = paymentStore.savePayment(payment.refund(amount, nextStatus, now()));
                    PaymentLedgerEntry completedLedger = new PaymentLedgerEntry(
                            reserved.ledger().id(),
                            reserved.ledger().paymentId(),
                            reserved.ledger().orderId(),
                            reserved.ledger().userId(),
                            reserved.ledger().type(),
                            amount,
                            PaymentLedgerStatus.SUCCESS,
                            reserved.ledger().requestKey(),
                            gatewayResult.providerTradeNo(),
                            reserved.ledger().createTime());
                    RefundResponseSnapshot responseSnapshot = RefundResponseSnapshot.capture(updated, completedLedger);
                    RefundAuditIntent pendingAudit = reserved.auditIntent()
                            .pending(refundAuditDetail(
                                    updated,
                                    completedLedger,
                                    reserved.auditIntent().includeOwner()));
                    PaymentStore.RefundRequest completed = paymentStore.saveLedger(
                            completedLedger,
                            fingerprint.value(),
                            reserved.operation().completed(),
                            reserved.merchantToken(),
                            responseSnapshot,
                            pendingAudit);
                    return new RefundCompletion(updated, completed, true);
                })
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Payment order does not exist")));
    }

    private RefundCompletion executeRefundGateway(
            RefundReservation reservation, String key, Consumer<PaymentOrder> accessCheck) {
        PaymentGatewayResult gatewayResult;
        try {
            gatewayResult = paymentGateway.refund(
                    reservation.payment(),
                    reservation.refund().ledger().amount(),
                    reservation.refund().merchantToken());
        } catch (PaymentGatewayException exception) {
            String terminalFailureCode = exception.isTerminal() ? exception.providerCode() : null;
            recordRefundGatewayFailure(reservation, exception.classification(), terminalFailureCode);
            throw exception.isTerminal() ? PaymentGatewayException.terminalReplay(terminalFailureCode) : exception;
        } catch (RuntimeException exception) {
            recordRefundGatewayFailure(reservation, PaymentFailureClassification.UNKNOWN, null);
            throw exception;
        }
        if (PaymentStatus.FAILED.equals(gatewayResult.status())) {
            PaymentGatewayException rejected = PaymentGatewayException.rejected("Payment provider rejected the refund");
            recordRefundGatewayFailure(reservation, rejected.classification(), rejected.providerCode());
            throw rejected;
        }
        if (PaymentStatus.PENDING.equals(gatewayResult.status())) {
            PaymentGatewayException unknown = new PaymentGatewayException(
                    PaymentFailureClassification.TIMEOUT_UNKNOWN, "Refund result is not yet known");
            recordRefundGatewayFailure(reservation, unknown.classification(), null);
            throw unknown;
        }
        try {
            return completeRefund(
                    reservation.payment().paymentNo(),
                    key,
                    reservation.fingerprint(),
                    reservation.refund().operation().attemptCount(),
                    gatewayResult,
                    accessCheck);
        } catch (RuntimeException exception) {
            recordRefundGatewayFailure(reservation, PaymentFailureClassification.LOCAL_COMPLETION, null);
            throw exception;
        }
    }

    private void recordRefundGatewayFailure(
            RefundReservation reservation, PaymentFailureClassification classification, String terminalFailureCode) {
        paymentTransactions.execute(() -> paymentStore
                .withLockedPayment(reservation.payment().paymentNo(), payment -> {
                    PaymentStore.RefundRequest latest = paymentStore
                            .findRefundRequest(
                                    payment.id(), reservation.refund().ledger().requestKey())
                            .orElse(null);
                    if (latest == null
                            || latest.operation().attemptCount()
                                    != reservation.refund().operation().attemptCount()
                            || !latest.operation().isRecoverable()) {
                        return latest;
                    }
                    boolean terminal = terminalFailureCode != null;
                    PaymentOperationAttempt operation = terminal
                            ? latest.operation().terminal(classification, terminalFailureCode)
                            : latest.operation().retryable(classification, now().plus(retryAfter));
                    PaymentLedgerEntry ledger =
                            terminal ? withLedgerStatus(latest.ledger(), PaymentLedgerStatus.FAILED) : latest.ledger();
                    return paymentStore.saveLedger(
                            ledger,
                            latest.requestFingerprint(),
                            operation,
                            latest.merchantToken(),
                            latest.responseSnapshot(),
                            latest.auditIntent());
                })
                .orElse(null));
    }

    private void dispatchRefundAudit(String paymentNo, String key) {
        try {
            paymentTransactions.execute(() -> paymentStore
                    .withLockedPayment(paymentNo, payment -> {
                        PaymentStore.RefundRequest refund = paymentStore
                                .findRefundRequest(payment.id(), key)
                                .orElse(null);
                        if (refund == null
                                || !PaymentOperationState.COMPLETED.equals(refund.operationState())
                                || !RefundAuditState.PENDING.equals(
                                        refund.auditIntent().state())) {
                            return null;
                        }
                        RefundAuditIntent audit = refund.auditIntent();
                        auditService.recordReliable(
                                audit.eventType(),
                                AuditService.OUTCOME_SUCCESS,
                                audit.actorUserId(),
                                audit.actorRole(),
                                payment.paymentNo(),
                                audit.sourceIp(),
                                audit.detail());
                        return paymentStore.saveLedger(
                                refund.ledger(),
                                refund.requestFingerprint(),
                                refund.operation(),
                                refund.merchantToken(),
                                refund.responseSnapshot(),
                                audit.delivered());
                    })
                    .orElse(null));
        } catch (RuntimeException ignored) {
            // The committed refund remains authoritative; PENDING is retried by replay or recovery.
        }
    }

    private static PaymentLedgerEntry withLedgerStatus(PaymentLedgerEntry ledger, PaymentLedgerStatus status) {
        return new PaymentLedgerEntry(
                ledger.id(),
                ledger.paymentId(),
                ledger.orderId(),
                ledger.userId(),
                ledger.type(),
                ledger.amount(),
                status,
                ledger.requestKey(),
                ledger.providerTradeNo(),
                ledger.createTime());
    }

    private static String refundAuditDetail(PaymentOrder payment, PaymentLedgerEntry ledger, boolean includeOwner) {
        BigDecimal amount = ledger.amount();
        return includeOwner
                ? "orderId=" + payment.orderId() + ",ownerUserId=" + payment.userId() + ",amount=" + amount + ",status="
                        + payment.status()
                : "amount=" + amount + ",status=" + payment.status();
    }

    private static BusinessException paymentConflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message);
    }

    private static boolean causedByConstraint(
            DataIntegrityViolationException exception, Set<String> expectedConstraints) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException constraintViolation
                    && matchesConstraint(constraintViolation.getConstraintName(), expectedConstraints)) {
                return true;
            }
            String message = cause.getMessage();
            if (message != null && containsExpectedConstraintToken(message, expectedConstraints)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesConstraint(String actual, Set<String> expectedConstraints) {
        if (actual == null) {
            return false;
        }
        String normalized = actual.strip()
                .replace("`", "")
                .replace("\"", "")
                .replace("'", "")
                .toLowerCase(Locale.ROOT);
        int qualifier = normalized.lastIndexOf('.');
        String unqualified = qualifier < 0 ? normalized : normalized.substring(qualifier + 1);
        return expectedConstraints.contains(unqualified);
    }

    private static boolean containsExpectedConstraintToken(String message, Set<String> expectedConstraints) {
        for (Pattern pattern : CONSTRAINT_TOKEN_PATTERNS) {
            var matcher = pattern.matcher(message);
            while (matcher.find()) {
                if (matchesConstraint(matcher.group(1), expectedConstraints)) {
                    return true;
                }
            }
        }
        return false;
    }

    private PaymentOrder confirmPayment(PaymentOrder payment, String providerTradeNo, String requestKey) {
        if (!PaymentStatus.PENDING.equals(payment.status())) {
            return payment;
        }
        transitionResolver.nextStatus(payment.status(), PaymentEvent.CONFIRM);
        PaymentOrder updated = paymentStore.savePayment(payment.markPaid(providerTradeNo, now()));
        paymentStore
                .findLedger(payment.id(), PaymentLedgerType.PAY, requestKey)
                .orElseGet(() -> paymentStore.saveLedger(new PaymentLedgerEntry(
                        idGenerator.nextId(),
                        payment.id(),
                        payment.orderId(),
                        payment.userId(),
                        PaymentLedgerType.PAY,
                        payment.amount(),
                        PaymentLedgerStatus.SUCCESS,
                        requestKey,
                        providerTradeNo,
                        now())));
        audit(
                AuditService.PAYMENT_PAID,
                payment.userId(),
                payment.paymentNo(),
                null,
                "amount=" + payment.amount() + ",providerTradeNo=" + providerTradeNo);
        return updated;
    }

    private PaymentOrder failPayment(PaymentOrder payment, String requestKey) {
        if (!PaymentStatus.PENDING.equals(payment.status())) {
            return payment;
        }
        transitionResolver.nextStatus(payment.status(), PaymentEvent.FAIL);
        PaymentOrder failed = paymentStore.savePayment(payment.fail(now()));
        audit(AuditService.PAYMENT_FAILED, payment.userId(), payment.paymentNo(), null, "requestKey=" + requestKey);
        return failed;
    }

    private PaymentReconciliationReport reconcileInternal(
            PaymentMethod provider, LocalDate reportDate, List<ReconciliationLine> providerLines) {
        List<PaymentOrder> platformPayments = paymentStore.findPaidByProviderAndDate(provider, reportDate);
        if (providerLines == null || providerLines.isEmpty()) {
            return savePendingProviderDataReport(provider, reportDate, platformPayments);
        }
        Map<String, PaymentOrder> platformByPaymentNo = new LinkedHashMap<>();
        for (PaymentOrder payment : platformPayments) {
            platformByPaymentNo.put(payment.paymentNo(), payment);
        }
        Map<String, ReconciliationLine> providerByPaymentNo = new LinkedHashMap<>();
        for (ReconciliationLine line : providerLines) {
            providerByPaymentNo.put(line.paymentNo(), line);
        }
        List<String> issues = new ArrayList<>();
        for (PaymentOrder payment : platformPayments) {
            ReconciliationLine providerLine = providerByPaymentNo.get(payment.paymentNo());
            if (providerLine == null || payment.paidAmount().compareTo(providerLine.amount()) != 0) {
                issues.add("platform:" + payment.paymentNo());
                suspendIfPossible(payment);
            }
        }
        for (ReconciliationLine providerLine : providerLines) {
            if (!platformByPaymentNo.containsKey(providerLine.paymentNo())) {
                issues.add("provider:" + providerLine.paymentNo());
            }
        }
        BigDecimal platformAmount = sumPayments(platformPayments);
        BigDecimal providerAmount = sumLines(providerLines);
        BigDecimal diff = platformAmount.subtract(providerAmount).abs();
        ReconciliationStatus status = issues.isEmpty() && diff.compareTo(BigDecimal.ZERO) == 0
                ? ReconciliationStatus.BALANCED
                : ReconciliationStatus.SUSPENDED;
        PaymentReconciliationReport report = paymentStore.saveReport(new PaymentReconciliationReport(
                idGenerator.nextId(),
                provider,
                reportDate,
                platformAmount,
                providerAmount,
                diff,
                issues.size(),
                status,
                payload(provider, reportDate, issues, providerLines),
                now()));
        audit(
                AuditService.PAYMENT_RECONCILED,
                null,
                provider + ":" + reportDate,
                null,
                "status=" + status + ",issues=" + issues.size());
        return report;
    }

    private PaymentReconciliationReport savePendingProviderDataReport(
            PaymentMethod provider, LocalDate reportDate, List<PaymentOrder> platformPayments) {
        BigDecimal platformAmount = sumPayments(platformPayments);
        PaymentReconciliationReport report = paymentStore.saveReport(new PaymentReconciliationReport(
                idGenerator.nextId(),
                provider,
                reportDate,
                platformAmount,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                1,
                ReconciliationStatus.PENDING_PROVIDER_DATA,
                payload(provider, reportDate, List.of("provider-lines:missing"), List.of()),
                now()));
        audit(
                AuditService.PAYMENT_RECONCILED,
                null,
                provider + ":" + reportDate,
                null,
                "status=" + ReconciliationStatus.PENDING_PROVIDER_DATA + ",issues=1");
        return report;
    }

    private void suspendIfPossible(PaymentOrder payment) {
        if (PaymentStatus.PAID.equals(payment.status()) || PaymentStatus.PARTIALLY_REFUNDED.equals(payment.status())) {
            transitionResolver.nextStatus(payment.status(), PaymentEvent.SUSPEND);
            paymentStore.savePayment(payment.suspend(now()));
        }
    }

    private void requireMfaForHighValue(Long userId, BigDecimal amount, String totpCode) {
        if (amount.compareTo(highValueThreshold) <= 0) {
            return;
        }
        UserAccount account = userAccountStore
                .findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN, "High-value payment requires TOTP"));
        if (!account.mfaEnabled() || !userMfaVerifier.verifyCode(account.totpSecret(), totpCode)) {
            audit(AuditService.PAYMENT_HIGH_VALUE_DENIED, userId, "payment", null, "amount=" + amount + ",reason=totp");
            throw new BusinessException(ErrorCode.FORBIDDEN, "High-value payment requires TOTP");
        }
    }

    private void verifySignature(PaymentCallbackRequestDto request) {
        if (!StringUtils.hasText(request.signature())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Payment callback signature is invalid");
        }
        String expected = signature(
                request.provider(),
                request.paymentNo(),
                request.providerTradeNo(),
                money(request.amount()),
                request.status(),
                callbackSecret);
        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                request.signature().trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Payment callback signature is invalid");
        }
    }

    public static String signature(
            PaymentMethod provider,
            String paymentNo,
            String providerTradeNo,
            BigDecimal amount,
            String status,
            String secret) {
        return sha256Hex(
                provider + ":" + paymentNo + ":" + providerTradeNo + ":" + money(amount) + ":" + status + ":" + secret);
    }

    private PaymentOrder requirePayment(String paymentNo) {
        return paymentStore
                .findByPaymentNo(paymentNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Payment order does not exist"));
    }

    private static void requireOwnedPayment(PaymentOrder payment, Long userId) {
        if (!userId.equals(payment.userId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Payment order is not available for current user");
        }
    }

    private OrderRecord requireOrder(Long orderId) {
        return orderStore
                .findById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Order does not exist"));
    }

    private void requireMatchingOrderOwner(PaymentOrder payment) {
        OrderRecord order = requireOrder(payment.orderId());
        if (!Objects.equals(order.userId(), payment.userId())) {
            throw new BusinessException(ErrorCode.CONFLICT, "Payment owner does not match order owner");
        }
    }

    private static Long requireAdminUserId(SessionUser currentUser) {
        Long userId = requireUserId(currentUser);
        if (!"ADMIN".equals(currentUser.role())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Administrator payment access is required");
        }
        return userId;
    }

    private static ReconciliationLine toLine(ReconciliationLineDto line) {
        return new ReconciliationLine(line.paymentNo(), line.providerTradeNo(), line.amount());
    }

    private static void assertAmountMatches(BigDecimal expected, BigDecimal actual) {
        if (money(expected).compareTo(money(actual)) != 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "Payment callback amount does not match");
        }
    }

    private static void requireMatchingFingerprint(
            String storedFingerprint, PaymentRequestFingerprint requestFingerprint) {
        if (!requestFingerprint.value().equals(storedFingerprint)) {
            throw new BusinessException(
                    ErrorCode.CONFLICT, "Idempotency-Key was already used for a different payment request");
        }
    }

    private static String normalizedCardNo(PaymentMethod method, String bankCardNo) {
        if (!PaymentMethod.BANK_CARD.equals(method)) {
            return null;
        }
        if (!StringUtils.hasText(bankCardNo)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Bank card number is required");
        }
        String digits = bankCardNo.replaceAll("\\D", "");
        if (digits.length() < 12 || digits.length() > 19) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Bank card number is invalid");
        }
        return digits;
    }

    private static String last4(String cardNo) {
        return StringUtils.hasText(cardNo) ? cardNo.substring(cardNo.length() - 4) : null;
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

    private static BigDecimal sumPayments(List<PaymentOrder> payments) {
        return money(payments.stream().map(PaymentOrder::paidAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private static BigDecimal sumLines(List<ReconciliationLine> lines) {
        return money(lines.stream().map(ReconciliationLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private static String payload(
            PaymentMethod provider, LocalDate reportDate, List<String> issues, List<ReconciliationLine> lines) {
        List<String> orderedIssues = issues.stream().sorted().toList();
        List<String> orderedLines = lines.stream()
                .sorted(Comparator.comparing(ReconciliationLine::paymentNo))
                .map(line -> line.paymentNo() + ":" + line.amount())
                .toList();
        return "provider=" + provider + ";date=" + reportDate + ";issues=" + orderedIssues + ";lines=" + orderedLines;
    }

    private void audit(String eventType, Long actorUserId, String subject, String sourceIp, String detail) {
        auditService.record(
                eventType, AuditService.OUTCOME_SUCCESS, actorUserId, CUSTOMER_ROLE, subject, sourceIp, detail);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest is unavailable", e);
        }
    }

    private static String requireCallbackSecret(String secret) {
        if (!StringUtils.hasText(secret)) {
            throw new IllegalStateException("APP_PAYMENT_CALLBACK_SECRET must be set");
        }
        return secret.trim();
    }

    private static Duration requirePositiveDuration(Duration configured, Duration defaultValue, String description) {
        Duration value = configured == null ? defaultValue : configured;
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(description + " must be positive");
        }
        return value;
    }

    private record RefundAuditContext(
            String eventType, Long actorUserId, String actorRole, String sourceIp, boolean includeOwner) {}

    private record PaymentCompletion(PaymentStore.PaymentIntent intent, boolean completedNow) {}

    private record PaymentReservation(PaymentStore.PaymentIntent intent, boolean ownedByCaller) {

        private PaymentReservation {
            Objects.requireNonNull(intent, "intent");
        }

        private static PaymentReservation owned(PaymentStore.PaymentIntent intent) {
            return new PaymentReservation(intent, true);
        }

        private static PaymentReservation requiresClaim(PaymentStore.PaymentIntent intent) {
            return new PaymentReservation(intent, false);
        }
    }

    private record RefundReservation(
            PaymentOrder payment, PaymentStore.RefundRequest refund, PaymentRequestFingerprint fingerprint) {}

    private record RefundCompletion(PaymentOrder payment, PaymentStore.RefundRequest refund, boolean completedNow) {}
}
