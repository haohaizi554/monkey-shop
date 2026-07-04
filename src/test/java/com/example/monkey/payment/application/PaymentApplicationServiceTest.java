package com.example.monkey.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.order.domain.OrderStore;
import com.example.monkey.order.domain.OrderStore.OrderRecord;
import com.example.monkey.payment.application.dto.PaymentCallbackRequestDto;
import com.example.monkey.payment.application.dto.PaymentCreateRequestDto;
import com.example.monkey.payment.application.dto.PaymentReconciliationRequestDto;
import com.example.monkey.payment.application.dto.PaymentRefundRequestDto;
import com.example.monkey.payment.application.dto.PaymentRefundResponseDto;
import com.example.monkey.payment.application.dto.PaymentResponseDto;
import com.example.monkey.payment.application.dto.ReconciliationLineDto;
import com.example.monkey.payment.domain.PaymentCallbackReplayGuard;
import com.example.monkey.payment.domain.PaymentEvent;
import com.example.monkey.payment.domain.PaymentGateway;
import com.example.monkey.payment.domain.PaymentGatewayResult;
import com.example.monkey.payment.domain.PaymentLedgerEntry;
import com.example.monkey.payment.domain.PaymentLedgerType;
import com.example.monkey.payment.domain.PaymentMethod;
import com.example.monkey.payment.domain.PaymentOrder;
import com.example.monkey.payment.domain.PaymentReconciliationReport;
import com.example.monkey.payment.domain.PaymentStatus;
import com.example.monkey.payment.domain.PaymentStore;
import com.example.monkey.payment.domain.PaymentTransitionPolicy;
import com.example.monkey.payment.domain.PaymentTransitionResolver;
import com.example.monkey.payment.domain.ReconciliationStatus;
import com.example.monkey.shared.application.observability.AuditService;
import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.shared.domain.id.IdGenerator;
import com.example.monkey.user.domain.UserAccountStore;
import com.example.monkey.user.domain.UserAccountStore.UserAccount;
import com.example.monkey.user.domain.UserMfaVerifier;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
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
class PaymentApplicationServiceTest {

    private static final String CALLBACK_SECRET = "secret";
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-07-04T08:30:00Z"), ZoneOffset.UTC);

    @Mock
    private OrderStore orderStore;

    @Mock
    private UserAccountStore userAccountStore;

    @Mock
    private UserMfaVerifier userMfaVerifier;

    @Mock
    private IdGenerator idGenerator;

    @Mock
    private AuditService auditService;

    private InMemoryPaymentStore paymentStore;
    private RecordingPaymentGateway paymentGateway;
    private PaymentApplicationService service;

    @BeforeEach
    void setUp() {
        paymentStore = new InMemoryPaymentStore();
        paymentGateway = new RecordingPaymentGateway();
        service = new PaymentApplicationService(
                paymentStore,
                paymentGateway,
                new InMemoryCallbackReplayGuard(),
                new PolicyPaymentTransitionResolver(),
                orderStore,
                userAccountStore,
                userMfaVerifier,
                idGenerator,
                auditService,
                FIXED_CLOCK,
                Duration.ofHours(24),
                Duration.ofMinutes(5),
                new BigDecimal("5000.00"),
                CALLBACK_SECRET);
    }

    @Test
    void highValueBankCardPaymentRequiresTotpAndKeepsMaskedCard() {
        when(orderStore.findVisibleByIdAndUserId(10L, 42L)).thenReturn(Optional.of(order(new BigDecimal("6000.00"))));
        when(userAccountStore.findById(42L)).thenReturn(Optional.of(account(true)));
        when(userMfaVerifier.verifyCode("totp-secret", "739201")).thenReturn(true);
        when(idGenerator.nextId()).thenReturn(1000L);

        PaymentResponseDto response = service.createPayment(
                user(),
                new PaymentCreateRequestDto(10L, PaymentMethod.BANK_CARD, "6222 0260 0670 5354 210", "739201"),
                "pay-key");

        assertThat(response.paymentNo()).isEqualTo("PAY1000");
        assertThat(response.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(response.bankCardLast4()).isEqualTo("4210");
        assertThat(response.paymentUrl()).isEqualTo("/sandbox/payments/PAY1000");
        verify(userMfaVerifier).verifyCode("totp-secret", "739201");
        verify(auditService)
                .record(
                        AuditService.PAYMENT_CREATED,
                        AuditService.OUTCOME_SUCCESS,
                        42L,
                        "CUSTOMER",
                        "PAY1000",
                        null,
                        "orderId=10,method=BANK_CARD");
    }

    @Test
    void highValuePaymentRejectsMissingTotp() {
        when(orderStore.findVisibleByIdAndUserId(10L, 42L)).thenReturn(Optional.of(order(new BigDecimal("6000.00"))));
        when(userAccountStore.findById(42L)).thenReturn(Optional.of(account(true)));

        assertThatThrownBy(() -> service.createPayment(
                        user(), new PaymentCreateRequestDto(10L, PaymentMethod.WECHAT, null, null), "pay-key"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void callbackVerifiesSignatureAndIsIdempotent() {
        paymentStore.savePayment(pendingPayment());
        when(idGenerator.nextId()).thenReturn(2000L);
        PaymentCallbackRequestDto request = callback("cb-1", "SUCCESS", new BigDecimal("100.00"));

        PaymentResponseDto first = service.handleCallback(request, "127.0.0.1");
        PaymentResponseDto replay = service.handleCallback(request, "127.0.0.1");

        assertThat(first.status()).isEqualTo(PaymentStatus.PAID);
        assertThat(replay.status()).isEqualTo(PaymentStatus.PAID);
        assertThat(paymentStore.ledgers).hasSize(1);
        assertThat(paymentStore.ledgers.get(0).type()).isEqualTo(PaymentLedgerType.PAY);
    }

    @Test
    void callbackRejectsInvalidSignatureBeforeStateChange() {
        paymentStore.savePayment(pendingPayment());
        PaymentCallbackRequestDto request = new PaymentCallbackRequestDto(
                PaymentMethod.WECHAT,
                "PAY100",
                "cb-1",
                "wx-trade-1",
                new BigDecimal("100.00"),
                "SUCCESS",
                "bad-signature");

        assertThatThrownBy(() -> service.handleCallback(request, "127.0.0.1"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThat(paymentStore.findByPaymentNo("PAY100"))
                .get()
                .extracting(PaymentOrder::status)
                .isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void partialRefundCreatesLedgerAndReusesIdempotentResult() {
        paymentStore.savePayment(paidPayment());
        when(idGenerator.nextId()).thenReturn(3000L);

        PaymentRefundResponseDto first = service.refund(
                user(), new PaymentRefundRequestDto("PAY100", new BigDecimal("30.00"), "partial"), "refund-key");
        PaymentRefundResponseDto replay = service.refund(
                user(), new PaymentRefundRequestDto("PAY100", new BigDecimal("30.00"), "partial"), "refund-key");

        assertThat(first.paymentStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(first.refundedAmount()).isEqualByComparingTo(new BigDecimal("30.00"));
        assertThat(replay.ledgerId()).isEqualTo(first.ledgerId());
        assertThat(paymentGateway.refundRequests).hasSize(1);
        assertThat(paymentStore.ledgers).singleElement().satisfies(ledger -> {
            assertThat(ledger.type()).isEqualTo(PaymentLedgerType.REFUND);
            assertThat(ledger.amount()).isEqualByComparingTo(new BigDecimal("30.00"));
        });
    }

    @Test
    void reconciliationSuspendsMismatchedPlatformPaymentsAndBuildsReport() {
        paymentStore.savePayment(paidPayment());
        when(idGenerator.nextId()).thenReturn(4000L);

        var response = service.reconcile(new PaymentReconciliationRequestDto(
                PaymentMethod.WECHAT,
                LocalDate.parse("2026-07-04"),
                List.of(new ReconciliationLineDto("PAY404", "missing", new BigDecimal("99.00")))));

        assertThat(response.status()).isEqualTo(ReconciliationStatus.SUSPENDED);
        assertThat(response.issueCount()).isEqualTo(2);
        assertThat(paymentStore.findByPaymentNo("PAY100"))
                .get()
                .extracting(PaymentOrder::status)
                .isEqualTo(PaymentStatus.SUSPENDED);
        assertThat(paymentStore.reports).singleElement().satisfies(report -> {
            assertThat(report.diffAmount()).isEqualByComparingTo(new BigDecimal("1.00"));
            assertThat(report.reportPayload()).contains("platform:PAY100", "provider:PAY404");
        });
    }

    @Test
    void scheduledReconciliationDoesNotSuspendPaidPaymentsWhenProviderLinesAreMissing() {
        paymentStore.savePayment(paidPaymentAt(LocalDateTime.parse("2026-07-03T08:10:00")));
        when(idGenerator.nextId()).thenReturn(4100L);

        var response = service.reconcileYesterday();

        assertThat(response.status()).isEqualTo(ReconciliationStatus.PENDING_PROVIDER_DATA);
        assertThat(response.issueCount()).isEqualTo(1);
        assertThat(paymentStore.findByPaymentNo("PAY100"))
                .get()
                .extracting(PaymentOrder::status)
                .isEqualTo(PaymentStatus.PAID);
        assertThat(paymentStore.reports).singleElement().satisfies(report -> {
            assertThat(report.platformAmount()).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(report.reportPayload()).contains("provider-lines:missing");
        });
    }

    @Test
    void timedOutQueryConfirmsPendingPayment() {
        paymentStore.savePayment(
                pendingPayment().withProviderTradeNo("wx-prepay-1", LocalDateTime.parse("2026-07-04T08:00:00")));
        paymentGateway.queryResult =
                new PaymentGatewayResult(PaymentStatus.PAID, "wx-trade-1", null, new BigDecimal("100.00"));
        when(idGenerator.nextId()).thenReturn(5000L);

        int handled = service.queryTimedOutPayments();

        assertThat(handled).isEqualTo(1);
        assertThat(paymentStore.findByPaymentNo("PAY100"))
                .get()
                .extracting(PaymentOrder::status)
                .isEqualTo(PaymentStatus.PAID);
        assertThat(paymentStore.ledgers)
                .singleElement()
                .extracting(PaymentLedgerEntry::requestKey)
                .isEqualTo("query:PAY100");
    }

    private static SessionUser user() {
        return new SessionUser(42L, "USER");
    }

    private static OrderRecord order(BigDecimal amount) {
        return new OrderRecord(
                10L,
                "ORD202607040001",
                42L,
                "buyer",
                "/images/avatar/buyer.png",
                7L,
                "Momo",
                "/images/product/momo.png",
                amount,
                "calm",
                "Ada",
                "13800138000",
                "Hangzhou",
                null,
                "PAID",
                LocalDateTime.parse("2026-07-04T08:00:00"),
                false);
    }

    private static UserAccount account(boolean mfaEnabled) {
        return new UserAccount(
                42L,
                "buyer",
                "hash",
                "13800138000",
                null,
                null,
                "USER",
                "buyer",
                LocalDateTime.parse("2026-07-01T08:00:00"),
                false,
                "totp-secret",
                mfaEnabled,
                List.of("ORDER_CREATE", "ORDER_READ_OWN"));
    }

    private static PaymentOrder pendingPayment() {
        return new PaymentOrder(
                100L,
                "PAY100",
                10L,
                42L,
                PaymentMethod.WECHAT,
                new BigDecimal("100.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                PaymentStatus.PENDING,
                "pay-key",
                null,
                null,
                null,
                null,
                null,
                LocalDateTime.parse("2026-07-04T08:00:00"),
                LocalDateTime.parse("2026-07-04T08:00:00"));
    }

    private static PaymentOrder paidPayment() {
        return paidPaymentAt(LocalDateTime.parse("2026-07-04T08:10:00"));
    }

    private static PaymentOrder paidPaymentAt(LocalDateTime paidAt) {
        return new PaymentOrder(
                100L,
                "PAY100",
                10L,
                42L,
                PaymentMethod.WECHAT,
                new BigDecimal("100.00"),
                new BigDecimal("100.00"),
                BigDecimal.ZERO,
                PaymentStatus.PAID,
                "pay-key",
                "wx-trade-1",
                null,
                null,
                null,
                paidAt,
                LocalDateTime.parse("2026-07-04T08:00:00"),
                paidAt);
    }

    private static PaymentCallbackRequestDto callback(String callbackId, String status, BigDecimal amount) {
        String signature = PaymentApplicationService.signature(
                PaymentMethod.WECHAT, "PAY100", "wx-trade-1", amount, status, CALLBACK_SECRET);
        return new PaymentCallbackRequestDto(
                PaymentMethod.WECHAT, "PAY100", callbackId, "wx-trade-1", amount, status, signature);
    }

    private static final class PolicyPaymentTransitionResolver implements PaymentTransitionResolver {
        @Override
        public PaymentStatus nextStatus(PaymentStatus currentStatus, PaymentEvent event) {
            return PaymentTransitionPolicy.nextStatus(currentStatus, event)
                    .orElseThrow(() -> new BusinessException(
                            ErrorCode.CONFLICT, PaymentTransitionPolicy.STATUS_TRANSITION_NOT_ALLOWED));
        }
    }

    private static final class InMemoryCallbackReplayGuard implements PaymentCallbackReplayGuard {
        private final List<String> keys = new ArrayList<>();

        @Override
        public boolean reserve(PaymentMethod provider, String paymentNo, String callbackId, Duration ttl) {
            String key = provider + ":" + callbackId;
            if (keys.contains(key)) {
                return false;
            }
            keys.add(key);
            return true;
        }
    }

    private static final class RecordingPaymentGateway implements PaymentGateway {
        private PaymentGatewayResult queryResult =
                new PaymentGatewayResult(PaymentStatus.PENDING, null, null, BigDecimal.ZERO);
        private final List<String> refundRequests = new ArrayList<>();

        @Override
        public PaymentGatewayResult create(PaymentOrder payment) {
            return new PaymentGatewayResult(
                    PaymentStatus.PENDING,
                    "SANDBOX-" + payment.paymentNo(),
                    "/sandbox/payments/" + payment.paymentNo(),
                    payment.amount());
        }

        @Override
        public PaymentGatewayResult query(PaymentOrder payment) {
            return queryResult;
        }

        @Override
        public PaymentGatewayResult refund(PaymentOrder payment, BigDecimal amount, String requestKey) {
            refundRequests.add(requestKey);
            return new PaymentGatewayResult(
                    PaymentStatus.PARTIALLY_REFUNDED, "REFUND-" + payment.paymentNo(), null, amount);
        }
    }

    private static final class InMemoryPaymentStore implements PaymentStore {
        private final Map<Long, PaymentOrder> payments = new LinkedHashMap<>();
        private final List<PaymentLedgerEntry> ledgers = new ArrayList<>();
        private final List<PaymentReconciliationReport> reports = new ArrayList<>();

        @Override
        public Optional<PaymentOrder> findByPaymentNo(String paymentNo) {
            return payments.values().stream()
                    .filter(payment -> payment.paymentNo().equals(paymentNo))
                    .findFirst();
        }

        @Override
        public Optional<PaymentOrder> findByOrderIdAndUserId(Long orderId, Long userId) {
            return payments.values().stream()
                    .filter(payment -> payment.orderId().equals(orderId)
                            && payment.userId().equals(userId))
                    .max(Comparator.comparing(PaymentOrder::createTime));
        }

        @Override
        public Optional<PaymentOrder> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey) {
            return payments.values().stream()
                    .filter(payment -> payment.userId().equals(userId)
                            && payment.idempotencyKey().equals(idempotencyKey))
                    .findFirst();
        }

        @Override
        public Optional<PaymentLedgerEntry> findLedger(Long paymentId, PaymentLedgerType type, String requestKey) {
            return ledgers.stream()
                    .filter(ledger -> ledger.paymentId().equals(paymentId)
                            && ledger.type().equals(type)
                            && ledger.requestKey().equals(requestKey))
                    .findFirst();
        }

        @Override
        public PaymentOrder savePayment(PaymentOrder payment) {
            payments.put(payment.id(), payment);
            return payment;
        }

        @Override
        public PaymentLedgerEntry saveLedger(PaymentLedgerEntry ledger) {
            ledgers.add(ledger);
            return ledger;
        }

        @Override
        public List<PaymentOrder> findPendingCreatedBefore(LocalDateTime cutoff, int limit) {
            return payments.values().stream()
                    .filter(payment -> payment.status().equals(PaymentStatus.PENDING))
                    .filter(payment -> payment.createTime().isBefore(cutoff))
                    .sorted(Comparator.comparing(PaymentOrder::createTime))
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<PaymentOrder> findPaidByProviderAndDate(PaymentMethod provider, LocalDate reportDate) {
            LocalDateTime start = reportDate.atStartOfDay();
            LocalDateTime end = reportDate.plusDays(1).atStartOfDay();
            return payments.values().stream()
                    .filter(payment -> payment.method().equals(provider))
                    .filter(payment -> payment.paidAt() != null
                            && !payment.paidAt().isBefore(start)
                            && payment.paidAt().isBefore(end))
                    .filter(payment -> List.of(
                                    PaymentStatus.PAID, PaymentStatus.PARTIALLY_REFUNDED, PaymentStatus.REFUNDED)
                            .contains(payment.status()))
                    .toList();
        }

        @Override
        public PaymentReconciliationReport saveReport(PaymentReconciliationReport report) {
            reports.add(report);
            return report;
        }
    }
}
