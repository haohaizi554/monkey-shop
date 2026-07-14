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
import com.example.monkey.payment.domain.PaymentLedgerStatus;
import com.example.monkey.payment.domain.PaymentLedgerType;
import com.example.monkey.payment.domain.PaymentMethod;
import com.example.monkey.payment.domain.PaymentOperationState;
import com.example.monkey.payment.domain.PaymentOrder;
import com.example.monkey.payment.domain.PaymentReconciliationReport;
import com.example.monkey.payment.domain.PaymentRequestFingerprint;
import com.example.monkey.payment.domain.PaymentResponseSnapshot;
import com.example.monkey.payment.domain.PaymentStatus;
import com.example.monkey.payment.domain.PaymentStore;
import com.example.monkey.payment.domain.PaymentTransitionPolicy;
import com.example.monkey.payment.domain.PaymentTransitionResolver;
import com.example.monkey.payment.domain.ReconciliationStatus;
import com.example.monkey.payment.domain.RefundResponseSnapshot;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

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
    private RecordingTransactions transactions;
    private PaymentApplicationService service;

    @BeforeEach
    void setUp() {
        paymentStore = new InMemoryPaymentStore();
        paymentGateway = new RecordingPaymentGateway();
        transactions = new RecordingTransactions();
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
                transactions,
                FIXED_CLOCK,
                Duration.ofHours(24),
                Duration.ofMinutes(5),
                new BigDecimal("5000.00"),
                CALLBACK_SECRET);
    }

    @Test
    void createCommitsReservationBeforeGatewayAndCompletesInANewTransaction() {
        when(orderStore.findVisibleByIdAndUserId(10L, 42L))
                .thenReturn(Optional.of(orderForId(10L, new BigDecimal("100.00"))));
        when(idGenerator.nextId()).thenReturn(1000L);
        paymentStore.boundaryEvents = transactions.events;
        paymentGateway.boundaryEvents = transactions.events;

        service.createPayment(
                user(), new PaymentCreateRequestDto(10L, PaymentMethod.WECHAT, null, null), "payment-key");

        assertThat(transactions.events)
                .containsExactly(
                        "transaction-begin",
                        "payment-reservation",
                        "transaction-commit",
                        "gateway-create",
                        "transaction-begin",
                        "payment-completion",
                        "transaction-commit");
    }

    @Test
    void refundCommitsReservationBeforeGatewayAndCompletesInANewTransaction() {
        paymentStore.savePayment(paidPayment());
        when(idGenerator.nextId()).thenReturn(3000L);
        paymentStore.boundaryEvents = transactions.events;
        paymentGateway.boundaryEvents = transactions.events;

        service.refund(user(), new PaymentRefundRequestDto("PAY100", new BigDecimal("30.00"), "return"), "refund-key");

        assertThat(transactions.events)
                .containsExactly(
                        "transaction-begin",
                        "refund-reservation",
                        "transaction-commit",
                        "gateway-refund",
                        "transaction-begin",
                        "payment-update",
                        "refund-completion",
                        "transaction-commit");
    }

    @Test
    void gatewayOrchestrationEntrypointsDoNotOwnAWholeTransaction() throws NoSuchMethodException {
        assertThat(PaymentApplicationService.class
                        .getMethod("createPayment", SessionUser.class, PaymentCreateRequestDto.class, String.class)
                        .getAnnotation(Transactional.class))
                .isNull();
        assertThat(PaymentApplicationService.class
                        .getMethod("refund", SessionUser.class, PaymentRefundRequestDto.class, String.class)
                        .getAnnotation(Transactional.class))
                .isNull();
        assertThat(PaymentApplicationService.class
                        .getMethod(
                                "refundAsAdmin",
                                SessionUser.class,
                                PaymentRefundRequestDto.class,
                                String.class,
                                String.class)
                        .getAnnotation(Transactional.class))
                .isNull();
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
    void createPaymentReusesMatchingFingerprintResponse() {
        when(orderStore.findVisibleByIdAndUserId(10L, 42L))
                .thenReturn(Optional.of(orderForId(10L, new BigDecimal("100.00"))));
        when(idGenerator.nextId()).thenReturn(1000L);

        PaymentResponseDto first = service.createPayment(
                user(), new PaymentCreateRequestDto(10L, PaymentMethod.WECHAT, null, null), "same-key");

        assertThat(service.createPayment(
                        user(), new PaymentCreateRequestDto(10L, PaymentMethod.WECHAT, null, null), "same-key"))
                .isEqualTo(first);
    }

    @Test
    void createPaymentReplayReturnsImmutableOriginalResponseAfterPaymentChanges() {
        when(orderStore.findVisibleByIdAndUserId(10L, 42L))
                .thenReturn(Optional.of(orderForId(10L, new BigDecimal("100.00"))));
        when(idGenerator.nextId()).thenReturn(1000L);

        PaymentResponseDto first = service.createPayment(
                user(), new PaymentCreateRequestDto(10L, PaymentMethod.WECHAT, null, null), "same-key");
        PaymentOrder changed = paymentStore
                .findByPaymentNo(first.paymentNo())
                .orElseThrow()
                .markPaid("provider-final-trade", LocalDateTime.parse("2026-07-04T09:00:00"));
        paymentStore.savePayment(changed);

        PaymentResponseDto replay = service.createPayment(
                user(), new PaymentCreateRequestDto(10L, PaymentMethod.WECHAT, null, null), "same-key");

        assertThat(replay).isEqualTo(first);
    }

    @Test
    void createPaymentRejectsIdempotencyKeyReusedForAnotherOrder() {
        when(orderStore.findVisibleByIdAndUserId(10L, 42L))
                .thenReturn(Optional.of(orderForId(10L, new BigDecimal("100.00"))));
        when(orderStore.findVisibleByIdAndUserId(11L, 42L))
                .thenReturn(Optional.of(orderForId(11L, new BigDecimal("100.00"))));
        when(idGenerator.nextId()).thenReturn(1000L);

        service.createPayment(user(), new PaymentCreateRequestDto(10L, PaymentMethod.WECHAT, null, null), "same-key");

        assertThatThrownBy(() -> service.createPayment(
                        user(), new PaymentCreateRequestDto(11L, PaymentMethod.WECHAT, null, null), "same-key"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    void concurrentCreateWithSameKeyAndFingerprintReplaysDatabaseWinner() throws Exception {
        when(orderStore.findVisibleByIdAndUserId(10L, 42L))
                .thenReturn(Optional.of(orderForId(10L, new BigDecimal("100.00"))));
        AtomicLong ids = new AtomicLong(1000L);
        when(idGenerator.nextId()).thenAnswer(ignored -> ids.getAndIncrement());
        paymentStore.synchronizeNextIntentReads(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            List<Future<PaymentResponseDto>> attempts = List.of(
                    executor.submit(() -> service.createPayment(
                            user(), new PaymentCreateRequestDto(10L, PaymentMethod.WECHAT, null, null), "same-key")),
                    executor.submit(() -> service.createPayment(
                            user(), new PaymentCreateRequestDto(10L, PaymentMethod.WECHAT, null, null), "same-key")));

            PaymentResponseDto first = attempts.get(0).get(5, TimeUnit.SECONDS);
            PaymentResponseDto second = attempts.get(1).get(5, TimeUnit.SECONDS);

            assertThat(second).isEqualTo(first);
            assertThat(paymentStore.payments).hasSize(1);
            assertThat(paymentGateway.createRequests).hasSizeGreaterThanOrEqualTo(1);
            assertThat(paymentGateway.createRequests.stream().distinct()).hasSize(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentCreateWithSameKeyAndDifferentFingerprintReturnsConflict() throws Exception {
        when(orderStore.findVisibleByIdAndUserId(10L, 42L))
                .thenReturn(Optional.of(orderForId(10L, new BigDecimal("100.00"))));
        when(orderStore.findVisibleByIdAndUserId(11L, 42L))
                .thenReturn(Optional.of(orderForId(11L, new BigDecimal("100.00"))));
        AtomicLong ids = new AtomicLong(1000L);
        when(idGenerator.nextId()).thenAnswer(ignored -> ids.getAndIncrement());
        paymentStore.synchronizeNextIntentReads(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            List<Future<PaymentResponseDto>> attempts = List.of(
                    executor.submit(() -> service.createPayment(
                            user(), new PaymentCreateRequestDto(10L, PaymentMethod.WECHAT, null, null), "same-key")),
                    executor.submit(() -> service.createPayment(
                            user(), new PaymentCreateRequestDto(11L, PaymentMethod.WECHAT, null, null), "same-key")));

            assertOneSuccessAndOneConflict(attempts);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentCreateForSameOrderWithDifferentKeysReturnsConflict() throws Exception {
        when(orderStore.findVisibleByIdAndUserId(10L, 42L))
                .thenReturn(Optional.of(orderForId(10L, new BigDecimal("100.00"))));
        AtomicLong ids = new AtomicLong(1000L);
        when(idGenerator.nextId()).thenAnswer(ignored -> ids.getAndIncrement());
        paymentStore.synchronizeNextIntentReads(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            List<Future<PaymentResponseDto>> attempts = List.of(
                    executor.submit(() -> service.createPayment(
                            user(), new PaymentCreateRequestDto(10L, PaymentMethod.WECHAT, null, null), "key-one")),
                    executor.submit(() -> service.createPayment(
                            user(), new PaymentCreateRequestDto(10L, PaymentMethod.WECHAT, null, null), "key-two")));

            assertOneSuccessAndOneConflict(attempts);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void createRecoversAfterGatewaySuccessAndLocalCompletionFailureWithStableToken() {
        when(orderStore.findVisibleByIdAndUserId(10L, 42L))
                .thenReturn(Optional.of(orderForId(10L, new BigDecimal("100.00"))));
        when(idGenerator.nextId()).thenReturn(1000L);
        paymentStore.failNextPaymentCompletion = true;

        assertThatThrownBy(() -> service.createPayment(
                        user(), new PaymentCreateRequestDto(10L, PaymentMethod.WECHAT, null, null), "payment-key"))
                .isInstanceOf(LocalCompletionFailure.class);

        PaymentResponseDto recovered = service.createPayment(
                user(), new PaymentCreateRequestDto(10L, PaymentMethod.WECHAT, null, null), "payment-key");

        assertThat(recovered.paymentNo()).isEqualTo("PAY1000");
        assertThat(paymentGateway.createRequests).containsExactly("PAY1000", "PAY1000");
        assertThat(paymentGateway.createRequests.stream().distinct()).hasSize(1);
    }

    @Test
    void createCompletionLocksTheCommittedReservationBeforeUpdatingIt() {
        when(orderStore.findVisibleByIdAndUserId(10L, 42L))
                .thenReturn(Optional.of(orderForId(10L, new BigDecimal("100.00"))));
        when(idGenerator.nextId()).thenReturn(1000L);
        paymentStore.requirePaymentLockForCompletion = true;

        PaymentResponseDto response = service.createPayment(
                user(), new PaymentCreateRequestDto(10L, PaymentMethod.WECHAT, null, null), "payment-key");

        assertThat(response.paymentNo()).isEqualTo("PAY1000");
    }

    @Test
    void legacyPaymentWithoutOriginalResponseSnapshotRejectsKeyReuse() {
        when(orderStore.findVisibleByIdAndUserId(10L, 42L))
                .thenReturn(Optional.of(orderForId(10L, new BigDecimal("100.00"))));
        PaymentRequestFingerprint fingerprint =
                PaymentRequestFingerprint.of(10L, PaymentMethod.WECHAT, new BigDecimal("100.00"), "CNY");
        paymentStore.saveLegacyPaymentIntent(pendingPayment(), fingerprint.value());

        assertThatThrownBy(() -> service.createPayment(
                        user(), new PaymentCreateRequestDto(10L, PaymentMethod.WECHAT, null, null), "pay-key"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.CONFLICT));

        assertThat(paymentGateway.createRequests).isEmpty();
    }

    @Test
    void requestFingerprintsCanonicalizeMoneyCurrencyEnumsAndRefundWhitespace() {
        assertThat(PaymentRequestFingerprint.of(10L, PaymentMethod.WECHAT, new BigDecimal("100.0"), "cny"))
                .isEqualTo(PaymentRequestFingerprint.of(10L, PaymentMethod.WECHAT, new BigDecimal("100.00"), "CNY"));
        assertThat(PaymentRequestFingerprint.ofRefund(100L, new BigDecimal("30.0"), "\u3000damaged\t item\u3000"))
                .isEqualTo(PaymentRequestFingerprint.ofRefund(100L, new BigDecimal("30.00"), "damaged item"));
        assertThat(PaymentRequestFingerprint.ofRefund(100L, new BigDecimal("30.00"), "\u00a0damaged\u2003item\u00a0"))
                .isEqualTo(PaymentRequestFingerprint.ofRefund(100L, new BigDecimal("30.00"), "damaged item"));
        assertThat(PaymentRequestFingerprint.ofRefund(100L, new BigDecimal("30.00"), "damaged item"))
                .isNotEqualTo(PaymentRequestFingerprint.ofRefund(100L, new BigDecimal("31.00"), "damaged item"));
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
    void olderRefundReplayReturnsImmutableResponseAfterLaterRefund() {
        paymentStore.savePayment(paidPayment());
        when(idGenerator.nextId()).thenReturn(3000L, 3001L);

        PaymentRefundResponseDto first = service.refund(
                user(), new PaymentRefundRequestDto("PAY100", new BigDecimal("30.00"), "first"), "refund-key-1");
        service.refund(
                user(), new PaymentRefundRequestDto("PAY100", new BigDecimal("20.00"), "second"), "refund-key-2");

        PaymentRefundResponseDto replay = service.refund(
                user(), new PaymentRefundRequestDto("PAY100", new BigDecimal("30.00"), "first"), "refund-key-1");

        assertThat(replay).isEqualTo(first);
    }

    @Test
    void refundReusesNormalizedFingerprintAndRejectsIdempotencyKeyCollision() {
        paymentStore.savePayment(paidPayment());
        when(idGenerator.nextId()).thenReturn(3000L);

        PaymentRefundResponseDto first = service.refund(
                user(),
                new PaymentRefundRequestDto("PAY100", new BigDecimal("30.0"), "\u3000damaged\t item\u3000"),
                "refund-key");

        assertThat(service.refund(
                        user(),
                        new PaymentRefundRequestDto("PAY100", new BigDecimal("30.00"), "damaged item"),
                        "refund-key"))
                .isEqualTo(first);
        assertThatThrownBy(() -> service.refund(
                        user(),
                        new PaymentRefundRequestDto("PAY100", new BigDecimal("31.00"), "damaged item"),
                        "refund-key"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    void concurrentRefundsWithDifferentIdempotencyKeysNeverExceedRefundableAmount() throws Exception {
        paymentStore.savePayment(paidPayment());
        paymentStore.synchronizeNextPaymentReads(2);
        AtomicLong ledgerIds = new AtomicLong(3000L);
        when(idGenerator.nextId()).thenAnswer(ignored -> ledgerIds.getAndIncrement());
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            List<Future<PaymentRefundResponseDto>> attempts = List.of(
                    executor.submit(() -> service.refund(
                            user(), new PaymentRefundRequestDto("PAY100", new BigDecimal("80.00"), "first"), "key-1")),
                    executor.submit(() -> service.refund(
                            user(),
                            new PaymentRefundRequestDto("PAY100", new BigDecimal("80.00"), "second"),
                            "key-2")));

            int successfulAttempts = 0;
            int rejectedAttempts = 0;
            for (Future<PaymentRefundResponseDto> attempt : attempts) {
                try {
                    attempt.get(5, TimeUnit.SECONDS);
                    successfulAttempts++;
                } catch (ExecutionException exception) {
                    assertThat(exception.getCause())
                            .isInstanceOfSatisfying(
                                    BusinessException.class,
                                    businessException -> assertThat(businessException.errorCode())
                                            .isEqualTo(ErrorCode.VALIDATION_ERROR));
                    rejectedAttempts++;
                }
            }

            assertThat(successfulAttempts).isEqualTo(1);
            assertThat(rejectedAttempts).isEqualTo(1);
            assertThat(paymentGateway.refundAmounts).containsExactly(new BigDecimal("80.00"));
            assertThat(paymentGateway.refundRequests).hasSize(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void gatewayFailureLeavesRefundStateUnchangedAndSameIdempotencyKeyCanRetry() {
        paymentStore.savePayment(paidPayment());
        when(idGenerator.nextId()).thenReturn(3000L);
        RuntimeException gatewayFailure = new IllegalStateException("refund gateway unavailable");
        paymentGateway.refundFailure = gatewayFailure;

        assertThatThrownBy(() -> service.refund(
                        user(),
                        new PaymentRefundRequestDto("PAY100", new BigDecimal("30.00"), "retryable"),
                        "retry-key"))
                .isSameAs(gatewayFailure);

        assertThat(paymentStore.findByPaymentNo("PAY100")).get().satisfies(payment -> {
            assertThat(payment.status()).isEqualTo(PaymentStatus.PAID);
            assertThat(payment.refundedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        });
        assertThat(paymentStore.ledgers).singleElement().satisfies(reservation -> {
            assertThat(reservation.id()).isEqualTo(3000L);
            assertThat(reservation.status()).isEqualTo(PaymentLedgerStatus.ACCEPTED);
        });

        paymentGateway.refundFailure = null;
        PaymentRefundResponseDto retry = service.refund(
                user(), new PaymentRefundRequestDto("PAY100", new BigDecimal("30.00"), "retryable"), "retry-key");

        assertThat(retry.refundedAmount()).isEqualByComparingTo(new BigDecimal("30.00"));
        assertThat(paymentGateway.refundRequests).containsExactly("PAY100:refund:3000", "PAY100:refund:3000");
    }

    @Test
    void refundRecoversAfterGatewaySuccessAndLocalCompletionFailureWithStableMerchantToken() {
        paymentStore.savePayment(paidPayment());
        when(idGenerator.nextId()).thenReturn(3000L);
        paymentGateway.afterNextRefund = () -> paymentStore.failNextPaymentUpdate = true;

        assertThatThrownBy(() -> service.refund(
                        user(),
                        new PaymentRefundRequestDto("PAY100", new BigDecimal("30.00"), "retryable"),
                        "refund-key"))
                .isInstanceOf(LocalCompletionFailure.class);

        PaymentRefundResponseDto recovered = service.refund(
                user(), new PaymentRefundRequestDto("PAY100", new BigDecimal("30.00"), "retryable"), "refund-key");

        assertThat(recovered.refundedAmount()).isEqualByComparingTo(new BigDecimal("30.00"));
        assertThat(paymentGateway.refundRequests).containsExactly("PAY100:refund:3000", "PAY100:refund:3000");
        assertThat(paymentGateway.refundRequests.stream().distinct()).hasSize(1);
    }

    @Test
    void legacyRefundWithUnknownReasonAndResponseRejectsKeyReuse() {
        paymentStore.savePayment(paidPayment());
        paymentStore.saveLegacyRefund(new PaymentLedgerEntry(
                2999L,
                100L,
                10L,
                42L,
                PaymentLedgerType.REFUND,
                new BigDecimal("30.00"),
                PaymentLedgerStatus.SUCCESS,
                "legacy-key",
                "legacy-provider-refund",
                LocalDateTime.parse("2026-07-04T08:20:00")));

        assertThatThrownBy(() -> service.refund(
                        user(),
                        new PaymentRefundRequestDto("PAY100", new BigDecimal("30.00"), "unknown legacy reason"),
                        "legacy-key"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.CONFLICT));

        assertThat(paymentGateway.refundRequests).isEmpty();
    }

    @Test
    void adminReadsAnotherUsersPaymentThroughDedicatedBoundary() {
        paymentStore.savePayment(paidPayment());
        when(orderStore.findById(10L)).thenReturn(Optional.of(order(new BigDecimal("100.00"))));

        PaymentResponseDto response = service.findByOrderAsAdmin(admin(), 10L, "10.0.0.8");

        assertThat(response.paymentNo()).isEqualTo("PAY100");
        assertThat(response.userId()).isEqualTo(42L);
        verify(auditService)
                .recordReliable(
                        AuditService.PAYMENT_ADMIN_READ,
                        AuditService.OUTCOME_SUCCESS,
                        1L,
                        "ADMIN",
                        "PAY100",
                        "10.0.0.8",
                        "orderId=10,ownerUserId=42");
    }

    @Test
    void adminRefundsAnotherUsersPaymentAndAuditsActorSeparatelyFromOwner() {
        paymentStore.savePayment(paidPayment());
        when(orderStore.findById(10L)).thenReturn(Optional.of(order(new BigDecimal("100.00"))));
        when(idGenerator.nextId()).thenReturn(3001L);

        PaymentRefundResponseDto response = service.refundAsAdmin(
                admin(),
                new PaymentRefundRequestDto("PAY100", new BigDecimal("30.00"), "approved return"),
                "admin-refund-key",
                "10.0.0.8");

        assertThat(response.paymentStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(paymentGateway.refundRequests).containsExactly("PAY100:refund:3001");
        verify(auditService)
                .recordReliable(
                        AuditService.PAYMENT_ADMIN_REFUNDED,
                        AuditService.OUTCOME_SUCCESS,
                        1L,
                        "ADMIN",
                        "PAY100",
                        "10.0.0.8",
                        "orderId=10,ownerUserId=42,amount=30.00,status=PARTIALLY_REFUNDED");
    }

    @Test
    void adminPaymentBoundaryRejectsNonAdminCallers() {
        assertThatThrownBy(() -> service.findByOrderAsAdmin(user(), 10L, "10.0.0.9"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void adminRefundRejectsMismatchedOrderAndPaymentOwnership() {
        paymentStore.savePayment(paidPayment());
        when(orderStore.findById(10L)).thenReturn(Optional.of(orderForUser(99L, new BigDecimal("100.00"))));

        assertThatThrownBy(() -> service.refundAsAdmin(
                        admin(),
                        new PaymentRefundRequestDto("PAY100", new BigDecimal("30.00"), "approved return"),
                        "admin-refund-key",
                        "10.0.0.8"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.CONFLICT));

        assertThat(paymentGateway.refundRequests).isEmpty();
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

    private static SessionUser admin() {
        return new SessionUser(1L, "ADMIN");
    }

    private static OrderRecord order(BigDecimal amount) {
        return orderForUser(42L, amount);
    }

    private static OrderRecord orderForUser(Long userId, BigDecimal amount) {
        return orderForIdAndUser(10L, userId, amount);
    }

    private static OrderRecord orderForId(Long orderId, BigDecimal amount) {
        return orderForIdAndUser(orderId, 42L, amount);
    }

    private static OrderRecord orderForIdAndUser(Long orderId, Long userId, BigDecimal amount) {
        return new OrderRecord(
                orderId,
                "ORD" + orderId,
                userId,
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

    private static void assertOneSuccessAndOneConflict(List<Future<PaymentResponseDto>> attempts) throws Exception {
        int successes = 0;
        int conflicts = 0;
        for (Future<PaymentResponseDto> attempt : attempts) {
            try {
                attempt.get(5, TimeUnit.SECONDS);
                successes++;
            } catch (ExecutionException exception) {
                assertThat(exception.getCause())
                        .isInstanceOfSatisfying(
                                BusinessException.class,
                                businessException -> assertThat(businessException.errorCode())
                                        .isEqualTo(ErrorCode.CONFLICT));
                conflicts++;
            }
        }
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);
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
        private final List<String> refundRequests = new CopyOnWriteArrayList<>();
        private final List<String> createRequests = new CopyOnWriteArrayList<>();
        private final List<BigDecimal> refundAmounts = new CopyOnWriteArrayList<>();
        private RuntimeException refundFailure;
        private Runnable afterNextRefund;
        private List<String> boundaryEvents;

        @Override
        public PaymentGatewayResult create(PaymentOrder payment, String merchantToken) {
            recordBoundary("gateway-create");
            createRequests.add(merchantToken);
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
        public PaymentGatewayResult refund(PaymentOrder payment, BigDecimal amount, String merchantToken) {
            recordBoundary("gateway-refund");
            refundRequests.add(merchantToken);
            refundAmounts.add(amount);
            if (refundFailure != null) {
                throw refundFailure;
            }
            Runnable afterRefund = afterNextRefund;
            afterNextRefund = null;
            if (afterRefund != null) {
                afterRefund.run();
            }
            return new PaymentGatewayResult(
                    PaymentStatus.PARTIALLY_REFUNDED, "REFUND-" + payment.paymentNo(), null, amount);
        }

        private void recordBoundary(String event) {
            if (boundaryEvents != null) {
                boundaryEvents.add(event);
            }
        }
    }

    private static final class InMemoryPaymentStore implements PaymentStore {
        private final Map<Long, PaymentOrder> payments = new ConcurrentHashMap<>();
        private final Map<Long, String> paymentFingerprints = new ConcurrentHashMap<>();
        private final Map<Long, PaymentResponseSnapshot> paymentSnapshots = new ConcurrentHashMap<>();
        private final Map<Long, PaymentOperationState> paymentOperationStates = new ConcurrentHashMap<>();
        private final Map<Long, String> paymentMerchantTokens = new ConcurrentHashMap<>();
        private final List<PaymentLedgerEntry> ledgers = new CopyOnWriteArrayList<>();
        private final Map<Long, String> ledgerFingerprints = new ConcurrentHashMap<>();
        private final Map<Long, RefundResponseSnapshot> refundSnapshots = new ConcurrentHashMap<>();
        private final Map<Long, PaymentOperationState> refundOperationStates = new ConcurrentHashMap<>();
        private final Map<Long, String> refundMerchantTokens = new ConcurrentHashMap<>();
        private final List<PaymentReconciliationReport> reports = new ArrayList<>();
        private volatile CountDownLatch paymentReads;
        private volatile CountDownLatch intentReads;
        private List<String> boundaryEvents;
        private volatile boolean failNextPaymentCompletion;
        private volatile boolean failNextPaymentUpdate;
        private volatile boolean requirePaymentLockForCompletion;
        private final ThreadLocal<Boolean> paymentLockHeld = ThreadLocal.withInitial(() -> false);

        private void synchronizeNextPaymentReads(int parties) {
            paymentReads = new CountDownLatch(parties);
        }

        private void synchronizeNextIntentReads(int parties) {
            intentReads = new CountDownLatch(parties);
        }

        private void saveLegacyPaymentIntent(PaymentOrder payment, String requestFingerprint) {
            payments.put(payment.id(), payment);
            paymentFingerprints.put(payment.id(), requestFingerprint);
            paymentOperationStates.put(payment.id(), PaymentOperationState.LEGACY_UNREPLAYABLE);
        }

        private void saveLegacyRefund(PaymentLedgerEntry ledger) {
            ledgers.add(ledger);
            refundOperationStates.put(ledger.id(), PaymentOperationState.LEGACY_UNREPLAYABLE);
        }

        @Override
        public Optional<PaymentOrder> findByPaymentNo(String paymentNo) {
            Optional<PaymentOrder> result = payments.values().stream()
                    .filter(payment -> payment.paymentNo().equals(paymentNo))
                    .findFirst();
            CountDownLatch reads = paymentReads;
            if (reads != null) {
                reads.countDown();
                try {
                    if (!reads.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("Concurrent payment reads did not arrive in time");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("Interrupted while coordinating concurrent payment reads", exception);
                }
                paymentReads = null;
            }
            return result;
        }

        @Override
        public Optional<PaymentOrder> findByOrderIdAndUserId(Long orderId, Long userId) {
            return payments.values().stream()
                    .filter(payment -> payment.orderId().equals(orderId)
                            && payment.userId().equals(userId))
                    .max(Comparator.comparing(PaymentOrder::createTime));
        }

        @Override
        public Optional<PaymentIntent> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey) {
            Optional<PaymentIntent> result = payments.values().stream()
                    .filter(payment -> payment.userId().equals(userId)
                            && payment.idempotencyKey().equals(idempotencyKey))
                    .findFirst()
                    .map(payment -> new PaymentIntent(
                            payment,
                            paymentFingerprints.get(payment.id()),
                            paymentOperationStates.get(payment.id()),
                            paymentMerchantTokens.get(payment.id()),
                            paymentSnapshots.get(payment.id())));
            awaitConcurrentReads(intentReads, "payment intent");
            intentReads = null;
            return result;
        }

        @Override
        public Optional<PaymentIntent> findActiveByOrderId(Long orderId) {
            return payments.values().stream()
                    .filter(payment -> payment.orderId().equals(orderId))
                    .filter(payment -> List.of(
                                    PaymentStatus.PENDING,
                                    PaymentStatus.PAID,
                                    PaymentStatus.PARTIALLY_REFUNDED,
                                    PaymentStatus.SUSPENDED)
                            .contains(payment.status()))
                    .findFirst()
                    .map(payment -> new PaymentIntent(
                            payment,
                            paymentFingerprints.get(payment.id()),
                            paymentOperationStates.get(payment.id()),
                            paymentMerchantTokens.get(payment.id()),
                            paymentSnapshots.get(payment.id())));
        }

        @Override
        public synchronized <T> Optional<T> withLockedPayment(String paymentNo, Function<PaymentOrder, T> operation) {
            paymentLockHeld.set(true);
            try {
                return payments.values().stream()
                        .filter(payment -> payment.paymentNo().equals(paymentNo))
                        .findFirst()
                        .map(operation);
            } finally {
                paymentLockHeld.remove();
            }
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
        public Optional<RefundRequest> findRefundRequest(Long paymentId, String requestKey) {
            return findLedger(paymentId, PaymentLedgerType.REFUND, requestKey)
                    .map(ledger -> new RefundRequest(
                            ledger,
                            ledgerFingerprints.get(ledger.id()),
                            refundOperationStates.get(ledger.id()),
                            refundMerchantTokens.get(ledger.id()),
                            refundSnapshots.get(ledger.id())));
        }

        @Override
        public BigDecimal sumAcceptedRefundAmount(Long paymentId) {
            return ledgers.stream()
                    .filter(ledger -> ledger.paymentId().equals(paymentId))
                    .filter(ledger -> PaymentLedgerType.REFUND.equals(ledger.type()))
                    .filter(ledger -> PaymentLedgerStatus.ACCEPTED.equals(ledger.status()))
                    .map(PaymentLedgerEntry::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        @Override
        public PaymentOrder savePayment(PaymentOrder payment) {
            recordBoundary("payment-update");
            if (failNextPaymentUpdate) {
                failNextPaymentUpdate = false;
                throw new LocalCompletionFailure();
            }
            payments.put(payment.id(), payment);
            return payment;
        }

        @Override
        public synchronized PaymentIntent savePayment(
                PaymentOrder payment,
                String requestFingerprint,
                PaymentOperationState operationState,
                String merchantToken,
                PaymentResponseSnapshot responseSnapshot) {
            recordBoundary(responseSnapshot == null ? "payment-reservation" : "payment-completion");
            if (responseSnapshot == null) {
                boolean duplicateKey = payments.values().stream()
                        .anyMatch(existing -> existing.userId().equals(payment.userId())
                                && existing.idempotencyKey().equals(payment.idempotencyKey()));
                boolean duplicateActiveOrder = payments.values().stream()
                        .anyMatch(existing -> existing.orderId().equals(payment.orderId())
                                && List.of(
                                                PaymentStatus.PENDING,
                                                PaymentStatus.PAID,
                                                PaymentStatus.PARTIALLY_REFUNDED,
                                                PaymentStatus.SUSPENDED)
                                        .contains(existing.status()));
                if (duplicateKey || duplicateActiveOrder) {
                    throw new DataIntegrityViolationException("simulated payment unique constraint");
                }
            } else if (requirePaymentLockForCompletion && !paymentLockHeld.get()) {
                throw new LocalCompletionFailure();
            } else if (failNextPaymentCompletion) {
                failNextPaymentCompletion = false;
                throw new LocalCompletionFailure();
            }
            payments.put(payment.id(), payment);
            paymentFingerprints.put(payment.id(), requestFingerprint);
            paymentOperationStates.put(payment.id(), operationState);
            paymentMerchantTokens.put(payment.id(), merchantToken);
            if (responseSnapshot != null) {
                paymentSnapshots.put(payment.id(), responseSnapshot);
            }
            return new PaymentIntent(
                    payment, requestFingerprint, operationState, merchantToken, paymentSnapshots.get(payment.id()));
        }

        @Override
        public PaymentLedgerEntry saveLedger(PaymentLedgerEntry ledger) {
            ledgers.add(ledger);
            return ledger;
        }

        @Override
        public synchronized RefundRequest saveLedger(
                PaymentLedgerEntry ledger,
                String requestFingerprint,
                PaymentOperationState operationState,
                String merchantToken,
                RefundResponseSnapshot responseSnapshot) {
            recordBoundary(responseSnapshot == null ? "refund-reservation" : "refund-completion");
            if (PaymentOperationState.RESERVED.equals(operationState)
                    && ledgers.stream()
                            .anyMatch(existing -> existing.paymentId().equals(ledger.paymentId())
                                    && existing.type().equals(ledger.type())
                                    && existing.requestKey().equals(ledger.requestKey()))) {
                throw new DataIntegrityViolationException("simulated refund unique constraint");
            }
            ledgers.removeIf(existing -> existing.id().equals(ledger.id()));
            ledgers.add(ledger);
            ledgerFingerprints.put(ledger.id(), requestFingerprint);
            refundOperationStates.put(ledger.id(), operationState);
            refundMerchantTokens.put(ledger.id(), merchantToken);
            if (responseSnapshot != null) {
                refundSnapshots.put(ledger.id(), responseSnapshot);
            }
            return new RefundRequest(
                    ledger, requestFingerprint, operationState, merchantToken, refundSnapshots.get(ledger.id()));
        }

        private void recordBoundary(String event) {
            if (boundaryEvents != null) {
                boundaryEvents.add(event);
            }
        }

        private static void awaitConcurrentReads(CountDownLatch reads, String description) {
            if (reads == null) {
                return;
            }
            reads.countDown();
            try {
                if (!reads.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Concurrent " + description + " reads did not arrive in time");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(
                        "Interrupted while coordinating concurrent " + description + " reads", exception);
            }
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

    private static final class RecordingTransactions implements TransactionOperations {
        private final List<String> events = new CopyOnWriteArrayList<>();

        @Override
        public <T> T execute(TransactionCallback<T> action) {
            events.add("transaction-begin");
            try {
                T result = action.doInTransaction(new SimpleTransactionStatus());
                events.add("transaction-commit");
                return result;
            } catch (RuntimeException exception) {
                events.add("transaction-rollback");
                throw exception;
            }
        }
    }

    private static final class LocalCompletionFailure extends RuntimeException {}
}
