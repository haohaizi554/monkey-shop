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
import com.example.monkey.payment.domain.PaymentGateway;
import com.example.monkey.payment.domain.PaymentGatewayResult;
import com.example.monkey.payment.domain.PaymentLedgerEntry;
import com.example.monkey.payment.domain.PaymentLedgerStatus;
import com.example.monkey.payment.domain.PaymentLedgerType;
import com.example.monkey.payment.domain.PaymentMethod;
import com.example.monkey.payment.domain.PaymentOrder;
import com.example.monkey.payment.domain.PaymentReconciliationReport;
import com.example.monkey.payment.domain.PaymentStatus;
import com.example.monkey.payment.domain.PaymentStore;
import com.example.monkey.payment.domain.PaymentTransitionResolver;
import com.example.monkey.payment.domain.ReconciliationLine;
import com.example.monkey.payment.domain.ReconciliationStatus;
import com.example.monkey.shared.application.observability.AuditService;
import com.example.monkey.shared.application.security.SessionUser;
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
import java.util.regex.Pattern;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class PaymentApplicationService {

    private static final int QUERY_BATCH_SIZE = 100;
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;
    private static final Pattern IDEMPOTENCY_KEY_PATTERN = Pattern.compile("[A-Za-z0-9._:-]+");
    private static final String CUSTOMER_ROLE = "CUSTOMER";
    private static final String SYSTEM_ROLE = "SYSTEM";
    private static final BigDecimal DEFAULT_HIGH_VALUE_THRESHOLD = BigDecimal.valueOf(5000);

    private final PaymentStore paymentStore;
    private final PaymentGateway paymentGateway;
    private final PaymentCallbackReplayGuard callbackReplayGuard;
    private final PaymentTransitionResolver transitionResolver;
    private final OrderStore orderStore;
    private final UserAccountStore userAccountStore;
    private final UserMfaVerifier userMfaVerifier;
    private final IdGenerator idGenerator;
    private final AuditService auditService;
    private final Clock clock;
    private final Duration callbackTtl;
    private final Duration queryAfter;
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
            @Value("${app.payment.callback-ttl:PT24H}") Duration callbackTtl,
            @Value("${app.payment.query-after:PT5M}") Duration queryAfter,
            @Value("${app.payment.high-value-threshold:5000}") BigDecimal highValueThreshold,
            @Value("${app.payment.callback-secret:dev-payment-callback-secret}") String callbackSecret) {
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
                Clock.systemDefaultZone(),
                callbackTtl,
                queryAfter,
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
            Clock clock,
            Duration callbackTtl,
            Duration queryAfter,
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
        this.clock = clock;
        this.callbackTtl = callbackTtl == null ? Duration.ofHours(24) : callbackTtl;
        this.queryAfter = queryAfter == null ? Duration.ofMinutes(5) : queryAfter;
        this.highValueThreshold = highValueThreshold == null ? DEFAULT_HIGH_VALUE_THRESHOLD : highValueThreshold;
        this.callbackSecret = StringUtils.hasText(callbackSecret) ? callbackSecret : "dev-payment-callback-secret";
    }

    @WithSpan("payment.create")
    @Transactional
    public PaymentResponseDto createPayment(
            SessionUser currentUser, PaymentCreateRequestDto request, String idempotencyKey) {
        Long userId = requireUserId(currentUser);
        String key = normalizeIdempotencyKey(idempotencyKey);
        return paymentStore
                .findByUserIdAndIdempotencyKey(userId, key)
                .map(PaymentDtoAssembler::toResponse)
                .orElseGet(() -> createPaymentLocked(userId, request, key));
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
    @Transactional
    public PaymentRefundResponseDto refund(
            SessionUser currentUser, PaymentRefundRequestDto request, String idempotencyKey) {
        Long userId = requireUserId(currentUser);
        String key = normalizeIdempotencyKey(idempotencyKey);
        PaymentOrder payment = requireOwnedPayment(request.paymentNo(), userId);
        return paymentStore
                .findLedger(payment.id(), PaymentLedgerType.REFUND, key)
                .map(ledger -> PaymentDtoAssembler.toRefundResponse(payment, ledger))
                .orElseGet(() -> refundLocked(payment, request, key));
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
    public int queryTimedOutPayments() {
        int handled = 0;
        LocalDateTime cutoff = now().minus(queryAfter);
        for (PaymentOrder payment : paymentStore.findPendingCreatedBefore(cutoff, QUERY_BATCH_SIZE)) {
            PaymentGatewayResult result = paymentGateway.query(payment);
            if (PaymentStatus.PAID.equals(result.status())) {
                confirmPayment(payment, result.providerTradeNo(), "query:" + payment.paymentNo());
                handled++;
            } else if (PaymentStatus.FAILED.equals(result.status())) {
                failPayment(payment, "query:" + payment.paymentNo());
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

    private PaymentResponseDto createPaymentLocked(Long userId, PaymentCreateRequestDto request, String key) {
        OrderRecord order = orderStore
                .findVisibleByIdAndUserId(request.orderId(), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN, "Order is not available for payment"));
        BigDecimal amount = money(order.price());
        requireMfaForHighValue(userId, amount, request.totpCode());
        String cardNo = normalizedCardNo(request.method(), request.bankCardNo());
        Long id = idGenerator.nextId();
        LocalDateTime now = now();
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
                now,
                now);
        PaymentOrder saved = paymentStore.savePayment(payment);
        PaymentGatewayResult gatewayResult = paymentGateway.create(saved);
        PaymentOrder withTradeNo =
                paymentStore.savePayment(saved.withProviderTradeNo(gatewayResult.providerTradeNo(), now()));
        audit(
                AuditService.PAYMENT_CREATED,
                userId,
                withTradeNo.paymentNo(),
                null,
                "orderId=" + order.id() + ",method=" + request.method());
        return PaymentDtoAssembler.toResponse(withTradeNo, gatewayResult.paymentUrl());
    }

    private PaymentRefundResponseDto refundLocked(PaymentOrder payment, PaymentRefundRequestDto request, String key) {
        BigDecimal amount = money(request.amount());
        if (amount.compareTo(BigDecimal.ZERO) <= 0 || amount.compareTo(payment.refundableAmount()) > 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Refund amount exceeds refundable amount");
        }
        PaymentGatewayResult gatewayResult = paymentGateway.refund(payment, amount, key);
        PaymentEvent event = amount.compareTo(payment.refundableAmount()) == 0
                ? PaymentEvent.REFUND_ALL
                : PaymentEvent.REFUND_PARTIAL;
        PaymentStatus nextStatus = transitionResolver.nextStatus(payment.status(), event);
        PaymentOrder updated = paymentStore.savePayment(payment.refund(amount, nextStatus, now()));
        PaymentLedgerEntry ledger = paymentStore.saveLedger(new PaymentLedgerEntry(
                idGenerator.nextId(),
                payment.id(),
                payment.orderId(),
                payment.userId(),
                PaymentLedgerType.REFUND,
                amount,
                PaymentLedgerStatus.SUCCESS,
                key,
                gatewayResult.providerTradeNo(),
                now()));
        audit(
                AuditService.PAYMENT_REFUNDED,
                payment.userId(),
                payment.paymentNo(),
                null,
                "amount=" + amount + ",status=" + updated.status());
        return PaymentDtoAssembler.toRefundResponse(updated, ledger);
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

    private PaymentOrder requireOwnedPayment(String paymentNo, Long userId) {
        PaymentOrder payment = requirePayment(paymentNo);
        if (!userId.equals(payment.userId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Payment order is not available for current user");
        }
        return payment;
    }

    private static ReconciliationLine toLine(ReconciliationLineDto line) {
        return new ReconciliationLine(line.paymentNo(), line.providerTradeNo(), line.amount());
    }

    private static void assertAmountMatches(BigDecimal expected, BigDecimal actual) {
        if (money(expected).compareTo(money(actual)) != 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "Payment callback amount does not match");
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
}
