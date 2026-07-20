package com.example.monkey.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.monkey.order.domain.OrderStatus;
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
import com.example.monkey.payment.domain.PaymentQueryAttempt;
import com.example.monkey.payment.domain.PaymentReconciliationReport;
import com.example.monkey.payment.domain.PaymentRecoveryTenantSource;
import com.example.monkey.payment.domain.PaymentRequestFingerprint;
import com.example.monkey.payment.domain.PaymentResponseSnapshot;
import com.example.monkey.payment.domain.PaymentStatus;
import com.example.monkey.payment.domain.PaymentStore;
import com.example.monkey.payment.domain.PaymentTransitionPolicy;
import com.example.monkey.payment.domain.PaymentTransitionResolver;
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
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.LongStream;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class PaymentApplicationServiceTest {

    private static final String CALLBACK_SECRET = "secret";
    private static final String CUSTOMER_ROLE = "CUSTOMER";
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
    private List<Long> recoveryTenantIds;
    private PaymentRecoveryTenantSource recoveryTenantSource;
    private PaymentApplicationService service;

    @BeforeEach
    void setUp() {
        paymentStore = new InMemoryPaymentStore();
        paymentGateway = new RecordingPaymentGateway();
        transactions = new RecordingTransactions();
        recoveryTenantIds = List.of();
        recoveryTenantSource = (cutoff, afterTenantId, limit) -> recoveryTenantIds.stream()
                .filter(tenantId -> tenantId > afterTenantId)
                .limit(limit)
                .toList();
        service = newService(FIXED_CLOCK);
    }

    private PaymentApplicationService newService(Clock clock) {
        return new PaymentApplicationService(
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
                recoveryTenantSource,
                clock,
                Duration.ofHours(24),
                Duration.ofMinutes(5),
                new BigDecimal("5000.00"),
                CALLBACK_SECRET);
    }

    @Test
    void scheduledRecoveryDiscoversTenantsIsolatesFailuresAndRestoresContext() {
        recoveryTenantIds = List.of(1L, 2L);
        paymentStore.failingRecoveryTenantIds.add(1L);
        TenantContext.setTenantId(77L);

        try {
            service.recoverExpiredOperationsScheduled();

            assertThat(paymentStore.recoveryTenantQueries).containsExactly(1L, 2L);
            assertThat(TenantContext.currentTenantId()).contains(77L);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void scheduledRecoveryProcessesTenantAfterAFullFailingFirstPage() {
        SequencedRecoveryTenantSource tenantSource =
                new SequencedRecoveryTenantSource(List.of(tenantIds(1L, 100L), List.of(101L)));
        recoveryTenantSource = tenantSource;
        service = newService(FIXED_CLOCK);
        paymentStore.failingRecoveryTenantIds.add(1L);
        TenantContext.setTenantId(77L);

        try {
            service.recoverExpiredOperationsScheduled();

            assertThat(paymentStore.recoveryTenantQueries).contains(101L);
            assertThat(TenantContext.currentTenantId()).contains(77L);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void nonAdvancingRecoveryTenantPageFailsWithoutRepeatingTenantsAndRestoresContext() {
        SequencedRecoveryTenantSource tenantSource =
                new SequencedRecoveryTenantSource(List.of(tenantIds(1L, 100L), List.of(100L)));
        recoveryTenantSource = tenantSource;
        service = newService(FIXED_CLOCK);
        TenantContext.setTenantId(77L);

        try {
            assertThatThrownBy(service::recoverExpiredOperationsScheduled)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("did not advance");

            assertThat(tenantSource.calls()).isEqualTo(2);
            assertThat(paymentStore.recoveryTenantQueries).containsExactlyElementsOf(tenantIds(1L, 100L));
            assertThat(paymentStore.recoveryTenantQueries).doesNotHaveDuplicates();
            assertThat(TenantContext.currentTenantId()).contains(77L);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void malformedRecoveryTenantPageDoesNotStarveLaterTenants() {
        List<Long> malformedFirstPage = new ArrayList<>(tenantIds(2L, 98L));
        malformedFirstPage.add(0, 0L);
        malformedFirstPage.add(0, 1L);
        malformedFirstPage.add(0, 1L);
        SequencedRecoveryTenantSource tenantSource =
                new SequencedRecoveryTenantSource(List.of(malformedFirstPage, List.of(99L)));
        recoveryTenantSource = tenantSource;
        service = newService(FIXED_CLOCK);
        TenantContext.setTenantId(77L);

        try {
            service.recoverExpiredOperationsScheduled();

            assertThat(tenantSource.cursors()).containsExactly(0L, 98L);
            assertThat(paymentStore.recoveryTenantQueries).containsExactlyElementsOf(tenantIds(1L, 99L));
            assertThat(paymentStore.recoveryTenantQueries).doesNotHaveDuplicates();
            assertThat(TenantContext.currentTenantId()).contains(77L);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void unexpiredPaymentClaimDoesNotTriggerAnotherProviderCall() {
        when(orderStore.findVisibleByIdAndUserId(10L, 42L))
                .thenReturn(Optional.of(orderForId(10L, new BigDecimal("100.00"))));
        PaymentOrder payment = pendingPayment();
        PaymentRequestFingerprint fingerprint =
                PaymentRequestFingerprint.of(10L, PaymentMethod.WECHAT, new BigDecimal("100.00"), "CNY");
        paymentStore.savePayment(
                payment,
                fingerprint.value(),
                PaymentOperationAttempt.initial(LocalDateTime.parse("2026-07-04T08:30:00"), Duration.ofMinutes(2)),
                payment.paymentNo(),
                null);

        assertThatThrownBy(() -> service.createPayment(
                        user(), new PaymentCreateRequestDto(10L, PaymentMethod.WECHAT, null, null), "pay-key"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.CONFLICT));

        assertThat(paymentGateway.createRequests).isEmpty();
        assertThat(paymentStore
                        .findByUserIdAndIdempotencyKey(42L, "pay-key")
                        .orElseThrow()
                        .operation()
                        .attemptCount())
                .isEqualTo(1);
    }

    @Test
    void racedActivePaymentWinnerMustBeClaimedBeforeAnotherProviderCall() {
        when(orderStore.findVisibleByIdAndUserId(10L, 42L))
                .thenReturn(Optional.of(orderForId(10L, new BigDecimal("100.00"))));
        PaymentOrder winner = pendingPayment();
        PaymentRequestFingerprint fingerprint =
                PaymentRequestFingerprint.of(10L, PaymentMethod.WECHAT, new BigDecimal("100.00"), "CNY");
        PaymentOperationAttempt winnerAttempt =
                PaymentOperationAttempt.initial(LocalDateTime.parse("2026-07-04T08:30:00"), Duration.ofMinutes(2));
        paymentGateway.createRequests.add(winner.paymentNo());
        paymentStore.afterNextIntentRead(
                () -> paymentStore.savePayment(winner, fingerprint.value(), winnerAttempt, winner.paymentNo(), null));

        BusinessException conflict = catchThrowableOfType(
                () -> service.createPayment(
                        user(), new PaymentCreateRequestDto(10L, PaymentMethod.WECHAT, null, null), "pay-key"),
                BusinessException.class);

        assertThat(paymentGateway.createRequests).containsExactly("PAY100");
        assertThat(conflict).isNotNull();
        assertThat(conflict.errorCode()).isEqualTo(ErrorCode.CONFLICT);
        PaymentStore.PaymentIntent persistedWinner =
                paymentStore.findByUserIdAndIdempotencyKey(42L, "pay-key").orElseThrow();
        assertThat(persistedWinner.operation()).isEqualTo(winnerAttempt);
        assertThat(persistedWinner.responseSnapshot()).isNull();
    }

    @Test
    void racedCompletedPaymentWinnerReplaysWithoutAnotherProviderCall() {
        when(orderStore.findVisibleByIdAndUserId(10L, 42L))
                .thenReturn(Optional.of(orderForId(10L, new BigDecimal("100.00"))));
        PaymentOrder winner =
                pendingPayment().withProviderTradeNo("winner-trade", LocalDateTime.parse("2026-07-04T08:30:01"));
        PaymentRequestFingerprint fingerprint =
                PaymentRequestFingerprint.of(10L, PaymentMethod.WECHAT, new BigDecimal("100.00"), "CNY");
        PaymentOperationAttempt winnerAttempt = PaymentOperationAttempt.initial(
                        LocalDateTime.parse("2026-07-04T08:29:00"), Duration.ofSeconds(30))
                .completed();
        PaymentResponseSnapshot winnerSnapshot = PaymentResponseSnapshot.capture(winner, "/winner/payment-url");
        paymentGateway.createRequests.add(winner.paymentNo());
        paymentStore.afterNextIntentRead(() -> paymentStore.savePayment(
                winner, fingerprint.value(), winnerAttempt, winner.paymentNo(), winnerSnapshot));

        PaymentResponseDto replay = service.createPayment(
                user(), new PaymentCreateRequestDto(10L, PaymentMethod.WECHAT, null, null), "pay-key");

        assertThat(replay.paymentNo()).isEqualTo("PAY100");
        assertThat(replay.paymentUrl()).isEqualTo("/winner/payment-url");
        assertThat(paymentGateway.createRequests).containsExactly("PAY100");
        assertThat(paymentStore
                        .findByUserIdAndIdempotencyKey(42L, "pay-key")
                        .orElseThrow()
                        .operation())
                .isEqualTo(winnerAttempt);
    }

    @Test
    void racedTerminalPaymentWinnerReplaysWithoutAnotherProviderCall() {
        when(orderStore.findVisibleByIdAndUserId(10L, 42L))
                .thenReturn(Optional.of(orderForId(10L, new BigDecimal("100.00"))));
        PaymentOrder winner = pendingPayment().fail(LocalDateTime.parse("2026-07-04T08:30:01"));
        PaymentRequestFingerprint fingerprint =
                PaymentRequestFingerprint.of(10L, PaymentMethod.WECHAT, new BigDecimal("100.00"), "CNY");
        PaymentOperationAttempt winnerAttempt = PaymentOperationAttempt.initial(
                        LocalDateTime.parse("2026-07-04T08:29:00"), Duration.ofSeconds(30))
                .terminal(PaymentFailureClassification.PROVIDER_REJECTED, "CARD_DECLINED");
        paymentGateway.createRequests.add(winner.paymentNo());
        paymentStore.afterNextIntentRead(
                () -> paymentStore.savePayment(winner, fingerprint.value(), winnerAttempt, winner.paymentNo(), null));

        PaymentGatewayException replay = catchThrowableOfType(
                () -> service.createPayment(
                        user(), new PaymentCreateRequestDto(10L, PaymentMethod.WECHAT, null, null), "pay-key"),
                PaymentGatewayException.class);

        assertThat(replay.providerCode()).isEqualTo("CARD_DECLINED");
        assertThat(paymentGateway.createRequests).containsExactly("PAY100");
        assertThat(paymentStore
                        .findByUserIdAndIdempotencyKey(42L, "pay-key")
                        .orElseThrow()
                        .operation())
                .isEqualTo(winnerAttempt);
    }

    @Test
    void expiredPaymentClaimIsTakenOverBeforeForegroundProviderCall() {
        when(orderStore.findVisibleByIdAndUserId(10L, 42L))
                .thenReturn(Optional.of(orderForId(10L, new BigDecimal("100.00"))));
        PaymentOrder payment = pendingPayment();
        PaymentRequestFingerprint fingerprint =
                PaymentRequestFingerprint.of(10L, PaymentMethod.WECHAT, new BigDecimal("100.00"), "CNY");
        paymentStore.savePayment(
                payment,
                fingerprint.value(),
                new PaymentOperationAttempt(
                        PaymentOperationState.RESERVED,
                        1,
                        LocalDateTime.parse("2026-07-04T08:29:00"),
                        PaymentFailureClassification.NONE),
                payment.paymentNo(),
                null);

        service.createPayment(user(), new PaymentCreateRequestDto(10L, PaymentMethod.WECHAT, null, null), "pay-key");

        PaymentStore.PaymentIntent completed =
                paymentStore.findByUserIdAndIdempotencyKey(42L, "pay-key").orElseThrow();
        assertThat(completed.operation().state()).isEqualTo(PaymentOperationState.COMPLETED);
        assertThat(completed.operation().attemptCount()).isEqualTo(2);
        assertThat(paymentGateway.createRequests).containsExactly("PAY100");
    }

    @Test
    void unexpiredRefundClaimDoesNotTriggerAnotherProviderCall() {
        PaymentOrder payment = paymentStore.savePayment(paidPayment());
        PaymentLedgerEntry ledger = refundLedger(3000L, PaymentLedgerStatus.ACCEPTED, "refund-key");
        PaymentRequestFingerprint fingerprint =
                PaymentRequestFingerprint.ofRefund(payment.id(), ledger.amount(), "return");
        paymentStore.saveLedger(
                ledger,
                fingerprint.value(),
                PaymentOperationAttempt.initial(LocalDateTime.parse("2026-07-04T08:30:00"), Duration.ofMinutes(2)),
                "PAY100:refund:3000",
                null,
                RefundAuditIntent.waiting(AuditService.PAYMENT_REFUNDED, 42L, CUSTOMER_ROLE, null, false));

        assertThatThrownBy(() -> service.refund(
                        user(), new PaymentRefundRequestDto("PAY100", new BigDecimal("30.00"), "return"), "refund-key"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.CONFLICT));

        assertThat(paymentGateway.refundRequests).isEmpty();
        assertThat(paymentStore
                        .findRefundRequest(payment.id(), "refund-key")
                        .orElseThrow()
                        .operation()
                        .attemptCount())
                .isEqualTo(1);
    }

    @Test
    void expiredRefundClaimIsTakenOverBeforeForegroundProviderCall() {
        PaymentOrder payment = paymentStore.savePayment(paidPayment());
        PaymentLedgerEntry ledger = refundLedger(3000L, PaymentLedgerStatus.ACCEPTED, "refund-key");
        PaymentRequestFingerprint fingerprint =
                PaymentRequestFingerprint.ofRefund(payment.id(), ledger.amount(), "return");
        paymentStore.saveLedger(
                ledger,
                fingerprint.value(),
                new PaymentOperationAttempt(
                        PaymentOperationState.RETRYABLE,
                        1,
                        LocalDateTime.parse("2026-07-04T08:29:00"),
                        PaymentFailureClassification.TIMEOUT_UNKNOWN),
                "PAY100:refund:3000",
                null,
                RefundAuditIntent.waiting(AuditService.PAYMENT_REFUNDED, 42L, CUSTOMER_ROLE, null, false));

        service.refund(user(), new PaymentRefundRequestDto("PAY100", new BigDecimal("30.00"), "return"), "refund-key");

        PaymentStore.RefundRequest completed =
                paymentStore.findRefundRequest(payment.id(), "refund-key").orElseThrow();
        assertThat(completed.operation().state()).isEqualTo(PaymentOperationState.COMPLETED);
        assertThat(completed.operation().attemptCount()).isEqualTo(2);
        assertThat(paymentGateway.refundRequests).containsExactly("PAY100:refund:3000");
    }

    @Test
    void lateOlderFailureCannotMutateANewerPaymentClaim() throws Exception {
        MutableClock clock = new MutableClock(FIXED_CLOCK.instant());
        PaymentApplicationService fencedService = newService(clock);
        PaymentOrder payment = pendingPayment();
        PaymentRequestFingerprint fingerprint =
                PaymentRequestFingerprint.of(10L, PaymentMethod.WECHAT, new BigDecimal("100.00"), "CNY");
        paymentStore.savePayment(
                payment,
                fingerprint.value(),
                new PaymentOperationAttempt(
                        PaymentOperationState.RESERVED,
                        1,
                        LocalDateTime.parse("2026-07-04T08:29:00"),
                        PaymentFailureClassification.NONE),
                payment.paymentNo(),
                null);
        paymentGateway.coordinateTwoCreateWorkers(PaymentGatewayException.rejected("old-worker-declined"));
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Integer> older = executor.submit(fencedService::recoverExpiredOperations);
            paymentGateway.awaitFirstCreate();
            clock.advance(Duration.ofMinutes(3));
            Future<Integer> newer = executor.submit(fencedService::recoverExpiredOperations);
            paymentGateway.awaitSecondCreate();

            paymentGateway.releaseFirstCreate();
            assertThat(older.get(5, TimeUnit.SECONDS)).isEqualTo(1);
            PaymentStore.PaymentIntent afterLateFailure =
                    paymentStore.findByUserIdAndIdempotencyKey(42L, "pay-key").orElseThrow();
            assertThat(afterLateFailure.payment().status()).isEqualTo(PaymentStatus.PENDING);
            assertThat(afterLateFailure.operation().state()).isEqualTo(PaymentOperationState.RESERVED);
            assertThat(afterLateFailure.operation().attemptCount()).isEqualTo(3);

            paymentGateway.releaseSecondCreate();
            assertThat(newer.get(5, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(paymentStore
                            .findByUserIdAndIdempotencyKey(42L, "pay-key")
                            .orElseThrow()
                            .operation()
                            .state())
                    .isEqualTo(PaymentOperationState.COMPLETED);
        } finally {
            executor.shutdownNow();
        }
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
                        "transaction-commit",
                        "transaction-begin",
                        "payment-reservation",
                        "transaction-commit",
                        "transaction-begin",
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
                        "transaction-begin",
                        "transaction-commit",
                        "gateway-refund",
                        "transaction-begin",
                        "payment-update",
                        "refund-completion",
                        "transaction-commit",
                        "transaction-begin",
                        "refund-audit-delivery",
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
    void timedOutQueryGatewayOrchestrationDoesNotOwnAWholeTransaction() throws NoSuchMethodException {
        saveQueryReadyPayment(
                pendingPayment().withProviderTradeNo("wx-prepay-1", LocalDateTime.parse("2026-07-04T08:00:00")));
        recoveryTenantIds = List.of(1L);
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl("jdbc:h2:mem:payment-query-transaction-boundary");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
        TransactionInterceptor transactionInterceptor =
                new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource());
        ProxyFactory proxyFactory = new ProxyFactory(service);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice(transactionInterceptor);
        PaymentApplicationService proxiedService = (PaymentApplicationService) proxyFactory.getProxy();
        Transactional queryTransaction = PaymentApplicationService.class
                .getMethod("queryTimedOutPaymentsScheduled")
                .getAnnotation(Transactional.class);

        proxiedService.queryTimedOutPaymentsScheduled();

        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(queryTransaction)
                    .as("query scheduler orchestration transaction annotation")
                    .isNull();
            softly.assertThat(paymentGateway.queryTransactionStates)
                    .as("active transaction state at paymentGateway.query")
                    .containsExactly(false);
        });
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
    void completedReplayDoesNotRequireFreshTotpOrBankCardValidation() {
        when(orderStore.findVisibleByIdAndUserId(10L, 42L)).thenReturn(Optional.of(order(new BigDecimal("6000.00"))));
        when(userAccountStore.findById(42L)).thenReturn(Optional.of(account(true)));
        when(userMfaVerifier.verifyCode("totp-secret", "739201")).thenReturn(true);
        when(idGenerator.nextId()).thenReturn(1000L);
        PaymentResponseDto first = service.createPayment(
                user(),
                new PaymentCreateRequestDto(10L, PaymentMethod.BANK_CARD, "6222 0260 0670 5354 210", "739201"),
                "pay-key");
        clearInvocations(userAccountStore, userMfaVerifier);

        PaymentResponseDto replay = service.createPayment(
                user(), new PaymentCreateRequestDto(10L, PaymentMethod.BANK_CARD, null, null), "pay-key");

        assertThat(replay).isEqualTo(first);
        verifyNoInteractions(userAccountStore, userMfaVerifier);
    }

    @Test
    void mismatchedReplayReturnsConflictBeforeTotpOrBankCardValidation() {
        when(orderStore.findVisibleByIdAndUserId(10L, 42L)).thenReturn(Optional.of(order(new BigDecimal("6000.00"))));
        when(userAccountStore.findById(42L)).thenReturn(Optional.of(account(true)));
        when(userMfaVerifier.verifyCode("totp-secret", "739201")).thenReturn(true);
        when(idGenerator.nextId()).thenReturn(1000L);
        service.createPayment(
                user(), new PaymentCreateRequestDto(10L, PaymentMethod.WECHAT, null, "739201"), "pay-key");
        clearInvocations(userAccountStore, userMfaVerifier);

        assertThatThrownBy(() -> service.createPayment(
                        user(), new PaymentCreateRequestDto(10L, PaymentMethod.BANK_CARD, null, null), "pay-key"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        verifyNoInteractions(userAccountStore, userMfaVerifier);
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
    void staleNewPaymentReservationDoesNotCallProviderAfterTakeover() throws Exception {
        MutableClock clock = new MutableClock(FIXED_CLOCK.instant());
        PaymentApplicationService fencedService = newService(clock);
        when(orderStore.findVisibleByIdAndUserId(10L, 42L))
                .thenReturn(Optional.of(orderForId(10L, new BigDecimal("100.00"))));
        when(idGenerator.nextId()).thenReturn(1000L);
        transactions.pauseAfterCommit(2);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<PaymentResponseDto> staleWorker = executor.submit(() -> fencedService.createPayment(
                    user(), new PaymentCreateRequestDto(10L, PaymentMethod.WECHAT, null, null), "payment-key"));
            transactions.awaitPausedCommit();
            PaymentOperationAttempt initialAttempt = paymentStore.paymentOperation("PAY1000");
            clock.advance(Duration.ofMinutes(3));
            PaymentOperationAttempt newerAttempt = paymentStore
                    .withLockedPayment("PAY1000", ignored -> {
                        PaymentStore.PaymentIntent latest = paymentStore
                                .findByUserIdAndIdempotencyKey(42L, "payment-key")
                                .orElseThrow();
                        return paymentStore
                                .savePayment(
                                        latest.payment(),
                                        latest.requestFingerprint(),
                                        latest.operation()
                                                .claim(
                                                        LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC),
                                                        Duration.ofMinutes(2)),
                                        latest.merchantToken(),
                                        latest.responseSnapshot())
                                .operation();
                    })
                    .orElseThrow();
            transactions.releasePausedCommit();
            Throwable staleWorkerFailure = awaitWorkerFailure(staleWorker);

            SoftAssertions.assertSoftly(softly -> {
                softly.assertThat(initialAttempt.attemptCount()).isEqualTo(1);
                softly.assertThat(newerAttempt.attemptCount()).isEqualTo(2);
                softly.assertThat(paymentGateway.createRequests).isEmpty();
                softly.assertThat(paymentStore.paymentOperation("PAY1000")).isEqualTo(newerAttempt);
                softly.assertThat(staleWorkerFailure).matches(PaymentApplicationServiceTest::isSuccessfulOrConflict);
            });
        } finally {
            transactions.releasePausedCommit();
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentCreateWithSameKeyAndFingerprintDoesNotExecuteDatabaseWinnerTwice() throws Exception {
        when(orderStore.findVisibleByIdAndUserId(10L, 42L))
                .thenReturn(Optional.of(orderForId(10L, new BigDecimal("100.00"))));
        AtomicLong ids = new AtomicLong(1000L);
        when(idGenerator.nextId()).thenAnswer(ignored -> ids.getAndIncrement());
        paymentStore.synchronizeNextIntentReads(2);
        paymentGateway.blockFirstCreate();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        ExecutorCompletionService<PaymentResponseDto> attempts = new ExecutorCompletionService<>(executor);

        try {
            attempts.submit(() -> service.createPayment(
                    user(), new PaymentCreateRequestDto(10L, PaymentMethod.WECHAT, null, null), "same-key"));
            attempts.submit(() -> service.createPayment(
                    user(), new PaymentCreateRequestDto(10L, PaymentMethod.WECHAT, null, null), "same-key"));

            paymentGateway.awaitFirstCreate();
            Future<PaymentResponseDto> loser = attempts.poll(5, TimeUnit.SECONDS);
            assertThat(loser).isNotNull();
            assertThatThrownBy(() -> loser.get(5, TimeUnit.SECONDS))
                    .isInstanceOfSatisfying(
                            ExecutionException.class,
                            exception -> assertThat(exception.getCause())
                                    .isInstanceOfSatisfying(
                                            BusinessException.class,
                                            businessException -> assertThat(businessException.errorCode())
                                                    .isEqualTo(ErrorCode.CONFLICT)));

            paymentGateway.releaseFirstCreate();
            PaymentResponseDto winner = attempts.poll(5, TimeUnit.SECONDS).get(5, TimeUnit.SECONDS);
            assertThat(winner.paymentNo()).isIn("PAY1000", "PAY1001");
            assertThat(paymentStore.payments.values())
                    .singleElement()
                    .extracting(PaymentOrder::paymentNo)
                    .isEqualTo(winner.paymentNo());
            assertThat(paymentGateway.createRequests).containsExactly(winner.paymentNo());

            PaymentResponseDto replay = service.createPayment(
                    user(), new PaymentCreateRequestDto(10L, PaymentMethod.WECHAT, null, null), "same-key");
            assertThat(replay.paymentNo()).isEqualTo(winner.paymentNo());
            assertThat(paymentGateway.createRequests).containsExactly(winner.paymentNo());
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
    void unrelatedPaymentConstraintViolationIsNotMisclassifiedAsIdempotencyConflict() {
        when(orderStore.findVisibleByIdAndUserId(10L, 42L))
                .thenReturn(Optional.of(orderForId(10L, new BigDecimal("100.00"))));
        when(idGenerator.nextId()).thenReturn(1000L);
        DataIntegrityViolationException primaryKeyFailure =
                new DataIntegrityViolationException("Duplicate entry '1000' for key 'payment_order.PRIMARY'");
        paymentStore.nextPaymentReservationFailure = primaryKeyFailure;

        assertThatThrownBy(() -> service.createPayment(
                        user(), new PaymentCreateRequestDto(10L, PaymentMethod.WECHAT, null, null), "payment-key"))
                .isSameAs(primaryKeyFailure);
    }

    @Test
    void constraintNameMentionOutsideTheActualMysqlKeyTokenIsNotMisclassified() {
        when(orderStore.findVisibleByIdAndUserId(10L, 42L))
                .thenReturn(Optional.of(orderForId(10L, new BigDecimal("100.00"))));
        when(idGenerator.nextId()).thenReturn(1000L);
        DataIntegrityViolationException primaryKeyFailure = new DataIntegrityViolationException(
                "Duplicate entry '1000' for key 'payment_order.PRIMARY'; diagnostic:"
                        + " uk_payment_order_user_key was not the violated key");
        paymentStore.nextPaymentReservationFailure = primaryKeyFailure;

        assertThatThrownBy(() -> service.createPayment(
                        user(), new PaymentCreateRequestDto(10L, PaymentMethod.WECHAT, null, null), "payment-key"))
                .isSameAs(primaryKeyFailure);
    }

    @Test
    void exactQualifiedMysqlConstraintTokenIsClassified() {
        when(orderStore.findVisibleByIdAndUserId(10L, 42L))
                .thenReturn(Optional.of(orderForId(10L, new BigDecimal("100.00"))));
        when(idGenerator.nextId()).thenReturn(1000L);
        paymentStore.nextPaymentReservationFailure = new DataIntegrityViolationException(
                "Duplicate entry '42-payment-key' for key 'payment_order.uk_payment_order_user_key'");

        assertThatThrownBy(() -> service.createPayment(
                        user(), new PaymentCreateRequestDto(10L, PaymentMethod.WECHAT, null, null), "payment-key"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    void exactHibernateConstraintTokenIsClassified() {
        when(orderStore.findVisibleByIdAndUserId(10L, 42L))
                .thenReturn(Optional.of(orderForId(10L, new BigDecimal("100.00"))));
        when(idGenerator.nextId()).thenReturn(1000L);
        paymentStore.nextPaymentReservationFailure = new DataIntegrityViolationException(
                "could not execute statement [duplicate payment]" + " [constraint [uk_payment_order_active_order]]");

        assertThatThrownBy(() -> service.createPayment(
                        user(), new PaymentCreateRequestDto(10L, PaymentMethod.WECHAT, null, null), "payment-key"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    void createRecoversAfterGatewaySuccessAndLocalCompletionFailureWithStableToken() {
        MutableClock clock = new MutableClock(FIXED_CLOCK.instant());
        PaymentApplicationService fencedService = newService(clock);
        when(orderStore.findVisibleByIdAndUserId(10L, 42L))
                .thenReturn(Optional.of(orderForId(10L, new BigDecimal("100.00"))));
        when(idGenerator.nextId()).thenReturn(1000L);
        paymentStore.failNextPaymentCompletion = true;

        assertThatThrownBy(() -> fencedService.createPayment(
                        user(), new PaymentCreateRequestDto(10L, PaymentMethod.WECHAT, null, null), "payment-key"))
                .isInstanceOf(LocalCompletionFailure.class);

        clock.advance(Duration.ofSeconds(31));
        PaymentResponseDto recovered = fencedService.createPayment(
                user(), new PaymentCreateRequestDto(10L, PaymentMethod.WECHAT, null, null), "payment-key");

        assertThat(recovered.paymentNo()).isEqualTo("PAY1000");
        assertThat(paymentGateway.createRequests).containsExactly("PAY1000", "PAY1000");
        assertThat(paymentGateway.createRequests.stream().distinct()).hasSize(1);
    }

    @Test
    void timeoutMarksPaymentRetryableAndRetryUsesTheSameMerchantToken() {
        MutableClock clock = new MutableClock(FIXED_CLOCK.instant());
        PaymentApplicationService fencedService = newService(clock);
        when(orderStore.findVisibleByIdAndUserId(10L, 42L))
                .thenReturn(Optional.of(orderForId(10L, new BigDecimal("100.00"))));
        when(idGenerator.nextId()).thenReturn(1000L);
        PaymentGatewayException timeout = PaymentGatewayException.timeout("provider timed out");
        paymentGateway.createFailure = timeout;

        assertThatThrownBy(() -> fencedService.createPayment(
                        user(), new PaymentCreateRequestDto(10L, PaymentMethod.WECHAT, null, null), "payment-key"))
                .isSameAs(timeout);

        PaymentStore.PaymentIntent retryable =
                paymentStore.findByUserIdAndIdempotencyKey(42L, "payment-key").orElseThrow();
        assertThat(retryable.operation().state()).isEqualTo(PaymentOperationState.RETRYABLE);
        assertThat(retryable.operation().lastFailure()).isEqualTo(PaymentFailureClassification.TIMEOUT_UNKNOWN);

        paymentGateway.createFailure = null;
        clock.advance(Duration.ofSeconds(31));
        fencedService.createPayment(
                user(), new PaymentCreateRequestDto(10L, PaymentMethod.WECHAT, null, null), "payment-key");

        assertThat(paymentGateway.createRequests).containsExactly("PAY1000", "PAY1000");
    }

    @Test
    void recoveryClaimsEachPaymentUsingItsOwnCurrentTime() {
        MutableClock clock = new MutableClock(FIXED_CLOCK.instant());
        PaymentApplicationService fencedService = newService(clock);
        PaymentOperationAttempt expiredAttempt = new PaymentOperationAttempt(
                PaymentOperationState.RETRYABLE,
                1,
                LocalDateTime.parse("2026-07-04T08:29:00"),
                PaymentFailureClassification.TIMEOUT_UNKNOWN);
        PaymentOrder first = pendingPayment();
        PaymentOrder second = pendingPayment(101L, 11L, "second-pay-key");
        paymentStore.savePayment(
                first,
                PaymentRequestFingerprint.of(first.orderId(), first.method(), first.amount(), "CNY")
                        .value(),
                expiredAttempt,
                first.paymentNo(),
                null);
        paymentStore.savePayment(
                second,
                PaymentRequestFingerprint.of(second.orderId(), second.method(), second.amount(), "CNY")
                        .value(),
                expiredAttempt,
                second.paymentNo(),
                null);
        AtomicReference<LocalDateTime> secondLeaseAtProvider = new AtomicReference<>();
        AtomicReference<LocalDateTime> secondProviderTime = new AtomicReference<>();
        paymentGateway.beforeCreate = payment -> {
            if (second.paymentNo().equals(payment.paymentNo())) {
                secondLeaseAtProvider.set(
                        paymentStore.paymentOperation(payment.paymentNo()).leaseExpiresAt());
                secondProviderTime.set(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
            }
        };
        paymentGateway.afterNextCreate = () -> clock.advance(Duration.ofMinutes(3));

        int recovered = fencedService.recoverExpiredOperations();

        assertThat(recovered).isEqualTo(2);
        assertThat(paymentGateway.createRequests).containsExactly("PAY100", "PAY101");
        assertThat(secondLeaseAtProvider.get()).isAfter(secondProviderTime.get());
    }

    @Test
    void deterministicPaymentRejectionTerminatesReservationAndReleasesActiveOrder() {
        PaymentGatewayException unknown =
                PaymentGatewayException.rejected("UNRECOGNIZED_GATEWAY_DETAIL", "raw internal provider detail");
        assertThat(unknown.providerCode()).isEqualTo("PROVIDER_REJECTED");
        assertThat(unknown.getMessage()).doesNotContain("raw internal provider detail");
        when(orderStore.findVisibleByIdAndUserId(10L, 42L))
                .thenReturn(Optional.of(orderForId(10L, new BigDecimal("100.00"))));
        when(idGenerator.nextId()).thenReturn(1000L, 1001L);
        PaymentGatewayException rejected =
                PaymentGatewayException.rejected("CARD_DECLINED", "raw processor decline detail");
        paymentGateway.createFailure = rejected;

        PaymentGatewayException first = catchThrowableOfType(
                () -> service.createPayment(
                        user(), new PaymentCreateRequestDto(10L, PaymentMethod.WECHAT, null, null), "declined-key"),
                PaymentGatewayException.class);
        assertThat(first.providerCode()).isEqualTo("CARD_DECLINED");
        assertThat(first.getMessage()).doesNotContain("raw processor decline detail");

        PaymentStore.PaymentIntent failed =
                paymentStore.findByUserIdAndIdempotencyKey(42L, "declined-key").orElseThrow();
        assertThat(failed.payment().status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(failed.operation().state()).isEqualTo(PaymentOperationState.TERMINAL_FAILED);
        assertThat(failed.operation().terminalFailureCode()).isEqualTo("CARD_DECLINED");

        paymentGateway.createFailure = null;
        PaymentGatewayException replay = catchThrowableOfType(
                () -> service.createPayment(
                        user(), new PaymentCreateRequestDto(10L, PaymentMethod.WECHAT, null, null), "declined-key"),
                PaymentGatewayException.class);
        assertThat(replay.providerCode()).isEqualTo(first.providerCode());
        assertThat(replay.getMessage()).isEqualTo(first.getMessage());
        assertThat(paymentGateway.createRequests).containsExactly("PAY1000");
        assertThatThrownBy(() -> service.createPayment(
                        user(), new PaymentCreateRequestDto(10L, PaymentMethod.ALIPAY, null, null), "declined-key"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.CONFLICT));

        PaymentResponseDto replacement = service.createPayment(
                user(), new PaymentCreateRequestDto(10L, PaymentMethod.WECHAT, null, null), "replacement-key");
        assertThat(replacement.paymentNo()).isEqualTo("PAY1001");
    }

    @Test
    void expiredAbandonedPaymentIsClaimedAndCompletedByBackgroundRecovery() {
        PaymentOrder payment = pendingPayment();
        PaymentRequestFingerprint fingerprint =
                PaymentRequestFingerprint.of(10L, PaymentMethod.WECHAT, new BigDecimal("100.00"), "CNY");
        paymentStore.savePayment(
                payment,
                fingerprint.value(),
                new PaymentOperationAttempt(
                        PaymentOperationState.RESERVED,
                        1,
                        LocalDateTime.parse("2026-07-04T08:29:00"),
                        PaymentFailureClassification.NONE),
                payment.paymentNo(),
                null);

        int recovered = service.recoverExpiredOperations();

        PaymentStore.PaymentIntent completed =
                paymentStore.findByUserIdAndIdempotencyKey(42L, "pay-key").orElseThrow();
        assertThat(recovered).isEqualTo(1);
        assertThat(completed.operation().state()).isEqualTo(PaymentOperationState.COMPLETED);
        assertThat(completed.operation().attemptCount()).isEqualTo(2);
        assertThat(paymentGateway.createRequests).containsExactly("PAY100");
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
        when(orderStore.transitionStatus(10L, OrderStatus.PENDING_PAYMENT.label(), OrderStatus.PAID.label(), null))
                .thenReturn(1);
        when(idGenerator.nextId()).thenReturn(2000L);
        PaymentCallbackRequestDto request = callback("cb-1", "SUCCESS", new BigDecimal("100.00"));

        PaymentResponseDto first = service.handleCallback(request, "127.0.0.1");
        PaymentResponseDto replay = service.handleCallback(request, "127.0.0.1");

        assertThat(first.status()).isEqualTo(PaymentStatus.PAID);
        assertThat(replay.status()).isEqualTo(PaymentStatus.PAID);
        assertThat(paymentStore.ledgers).hasSize(1);
        assertThat(paymentStore.ledgers.get(0).type()).isEqualTo(PaymentLedgerType.PAY);
        verify(orderStore).transitionStatus(10L, OrderStatus.PENDING_PAYMENT.label(), OrderStatus.PAID.label(), null);
    }

    @Test
    void callbackAcceptsAnOrderThatAlreadyAdvancedBeyondPendingPayment() {
        paymentStore.savePayment(pendingPayment());
        when(orderStore.findById(10L)).thenReturn(Optional.of(order(new BigDecimal("100.00"))));
        when(idGenerator.nextId()).thenReturn(2001L);

        PaymentResponseDto response =
                service.handleCallback(callback("cb-advanced", "SUCCESS", new BigDecimal("100.00")), "127.0.0.1");

        assertThat(response.status()).isEqualTo(PaymentStatus.PAID);
        verify(orderStore).transitionStatus(10L, OrderStatus.PENDING_PAYMENT.label(), OrderStatus.PAID.label(), null);
    }

    @Test
    void callbackRejectsAnUnknownPersistedOrderState() {
        paymentStore.savePayment(pendingPayment());
        OrderRecord corruptOrder = mock(OrderRecord.class);
        when(corruptOrder.status()).thenReturn("CORRUPT");
        when(orderStore.findById(10L)).thenReturn(Optional.of(corruptOrder));
        when(idGenerator.nextId()).thenReturn(2002L);

        assertThatThrownBy(() -> service.handleCallback(
                        callback("cb-corrupt", "SUCCESS", new BigDecimal("100.00")), "127.0.0.1"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        verify(orderStore).transitionStatus(10L, OrderStatus.PENDING_PAYMENT.label(), OrderStatus.PAID.label(), null);
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
    void staleNewRefundReservationDoesNotCallProviderAfterTakeover() throws Exception {
        MutableClock clock = new MutableClock(FIXED_CLOCK.instant());
        PaymentApplicationService fencedService = newService(clock);
        paymentStore.savePayment(paidPayment());
        when(idGenerator.nextId()).thenReturn(3000L);
        transactions.pauseAfterCommit(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<PaymentRefundResponseDto> staleWorker = executor.submit(() -> fencedService.refund(
                    user(), new PaymentRefundRequestDto("PAY100", new BigDecimal("30.00"), "return"), "refund-key"));
            transactions.awaitPausedCommit();
            PaymentOperationAttempt initialAttempt = paymentStore
                    .findRefundRequest(100L, "refund-key")
                    .orElseThrow()
                    .operation();
            clock.advance(Duration.ofMinutes(3));
            PaymentOperationAttempt newerAttempt = paymentStore
                    .withLockedPayment("PAY100", payment -> {
                        PaymentStore.RefundRequest latest = paymentStore
                                .findRefundRequest(payment.id(), "refund-key")
                                .orElseThrow();
                        return paymentStore
                                .saveLedger(
                                        latest.ledger(),
                                        latest.requestFingerprint(),
                                        latest.operation()
                                                .claim(
                                                        LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC),
                                                        Duration.ofMinutes(2)),
                                        latest.merchantToken(),
                                        latest.responseSnapshot(),
                                        latest.auditIntent())
                                .operation();
                    })
                    .orElseThrow();
            transactions.releasePausedCommit();
            Throwable staleWorkerFailure = awaitWorkerFailure(staleWorker);

            SoftAssertions.assertSoftly(softly -> {
                softly.assertThat(initialAttempt.attemptCount()).isEqualTo(1);
                softly.assertThat(newerAttempt.attemptCount()).isEqualTo(2);
                softly.assertThat(paymentGateway.refundRequests).isEmpty();
                softly.assertThat(paymentStore
                                .findRefundRequest(100L, "refund-key")
                                .orElseThrow()
                                .operation())
                        .isEqualTo(newerAttempt);
                softly.assertThat(paymentStore
                                .findByPaymentNo("PAY100")
                                .orElseThrow()
                                .refundedAmount())
                        .isEqualByComparingTo(BigDecimal.ZERO);
                softly.assertThat(staleWorkerFailure).matches(PaymentApplicationServiceTest::isSuccessfulOrConflict);
            });
        } finally {
            transactions.releasePausedCommit();
            executor.shutdownNow();
        }
    }

    @Test
    void gatewayFailureLeavesRefundStateUnchangedAndSameIdempotencyKeyCanRetry() {
        MutableClock clock = new MutableClock(FIXED_CLOCK.instant());
        PaymentApplicationService fencedService = newService(clock);
        paymentStore.savePayment(paidPayment());
        when(idGenerator.nextId()).thenReturn(3000L);
        RuntimeException gatewayFailure = new IllegalStateException("refund gateway unavailable");
        paymentGateway.refundFailure = gatewayFailure;

        assertThatThrownBy(() -> fencedService.refund(
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
        clock.advance(Duration.ofSeconds(31));
        PaymentRefundResponseDto retry = fencedService.refund(
                user(), new PaymentRefundRequestDto("PAY100", new BigDecimal("30.00"), "retryable"), "retry-key");

        assertThat(retry.refundedAmount()).isEqualByComparingTo(new BigDecimal("30.00"));
        assertThat(paymentGateway.refundRequests).containsExactly("PAY100:refund:3000", "PAY100:refund:3000");
    }

    @Test
    void unrelatedRefundConstraintViolationIsNotMisclassifiedAsIdempotencyConflict() {
        paymentStore.savePayment(paidPayment());
        when(idGenerator.nextId()).thenReturn(3000L);
        DataIntegrityViolationException foreignKeyFailure =
                new DataIntegrityViolationException("Cannot add child row: constraint fk_payment_ledger_payment");
        paymentStore.nextRefundReservationFailure = foreignKeyFailure;

        assertThatThrownBy(() -> service.refund(
                        user(), new PaymentRefundRequestDto("PAY100", new BigDecimal("30.00"), "return"), "refund-key"))
                .isSameAs(foreignKeyFailure);
    }

    @Test
    void refundRecoversAfterGatewaySuccessAndLocalCompletionFailureWithStableMerchantToken() {
        MutableClock clock = new MutableClock(FIXED_CLOCK.instant());
        PaymentApplicationService fencedService = newService(clock);
        paymentStore.savePayment(paidPayment());
        when(idGenerator.nextId()).thenReturn(3000L);
        paymentGateway.afterNextRefund = () -> paymentStore.failNextPaymentUpdate = true;

        assertThatThrownBy(() -> fencedService.refund(
                        user(),
                        new PaymentRefundRequestDto("PAY100", new BigDecimal("30.00"), "retryable"),
                        "refund-key"))
                .isInstanceOf(LocalCompletionFailure.class);

        clock.advance(Duration.ofSeconds(31));
        PaymentRefundResponseDto recovered = fencedService.refund(
                user(), new PaymentRefundRequestDto("PAY100", new BigDecimal("30.00"), "retryable"), "refund-key");

        assertThat(recovered.refundedAmount()).isEqualByComparingTo(new BigDecimal("30.00"));
        assertThat(paymentGateway.refundRequests).containsExactly("PAY100:refund:3000", "PAY100:refund:3000");
        assertThat(paymentGateway.refundRequests.stream().distinct()).hasSize(1);
    }

    @Test
    void expiredRefundCanBeCompletedByBackgroundRecovery() {
        PaymentOrder payment = paymentStore.savePayment(paidPayment());
        PaymentLedgerEntry ledger = refundLedger(3000L, PaymentLedgerStatus.ACCEPTED, "abandoned-refund");
        PaymentRequestFingerprint fingerprint =
                PaymentRequestFingerprint.ofRefund(payment.id(), ledger.amount(), "abandoned");
        paymentStore.saveLedger(
                ledger,
                fingerprint.value(),
                new PaymentOperationAttempt(
                        PaymentOperationState.RETRYABLE,
                        1,
                        LocalDateTime.parse("2026-07-04T08:29:00"),
                        PaymentFailureClassification.TIMEOUT_UNKNOWN),
                "PAY100:refund:3000",
                null,
                RefundAuditIntent.waiting(AuditService.PAYMENT_REFUNDED, 42L, CUSTOMER_ROLE, null, false));

        int recovered = service.recoverExpiredOperations();

        PaymentStore.RefundRequest completed =
                paymentStore.findRefundRequest(payment.id(), "abandoned-refund").orElseThrow();
        assertThat(recovered).isEqualTo(1);
        assertThat(completed.ledger().status()).isEqualTo(PaymentLedgerStatus.SUCCESS);
        assertThat(completed.operation().state()).isEqualTo(PaymentOperationState.COMPLETED);
        assertThat(paymentGateway.refundRequests).containsExactly("PAY100:refund:3000");
    }

    @Test
    void deterministicRefundRejectionReleasesReservedRefundAmount() {
        paymentStore.savePayment(paidPayment());
        when(idGenerator.nextId()).thenReturn(3000L, 3001L);
        PaymentGatewayException rejected =
                PaymentGatewayException.rejected("REFUND_DECLINED", "raw refund processor detail");
        paymentGateway.refundFailure = rejected;

        PaymentGatewayException first = catchThrowableOfType(
                () -> service.refund(
                        user(),
                        new PaymentRefundRequestDto("PAY100", new BigDecimal("80.00"), "declined"),
                        "declined-key"),
                PaymentGatewayException.class);
        assertThat(first.providerCode()).isEqualTo("REFUND_DECLINED");
        assertThat(first.getMessage()).doesNotContain("raw refund processor detail");

        PaymentStore.RefundRequest failed =
                paymentStore.findRefundRequest(100L, "declined-key").orElseThrow();
        assertThat(failed.ledger().status()).isEqualTo(PaymentLedgerStatus.FAILED);
        assertThat(failed.operation().state()).isEqualTo(PaymentOperationState.TERMINAL_FAILED);
        assertThat(failed.operation().terminalFailureCode()).isEqualTo("REFUND_DECLINED");
        assertThat(paymentStore.sumAcceptedRefundAmount(100L)).isEqualByComparingTo(BigDecimal.ZERO);

        paymentGateway.refundFailure = null;
        PaymentGatewayException replay = catchThrowableOfType(
                () -> service.refund(
                        user(),
                        new PaymentRefundRequestDto("PAY100", new BigDecimal("80.00"), "declined"),
                        "declined-key"),
                PaymentGatewayException.class);
        assertThat(replay.providerCode()).isEqualTo(first.providerCode());
        assertThat(replay.getMessage()).isEqualTo(first.getMessage());
        assertThat(paymentGateway.refundRequests).containsExactly("PAY100:refund:3000");
        assertThatThrownBy(() -> service.refund(
                        user(),
                        new PaymentRefundRequestDto("PAY100", new BigDecimal("79.00"), "declined"),
                        "declined-key"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.CONFLICT));

        PaymentRefundResponseDto replacement = service.refund(
                user(), new PaymentRefundRequestDto("PAY100", new BigDecimal("80.00"), "retry"), "replacement-key");
        assertThat(replacement.refundedAmount()).isEqualByComparingTo(new BigDecimal("80.00"));
    }

    @Test
    void completedRefundRemainsSuccessfulWhilePendingReliableAuditIsRetriedExactlyOnce() {
        paymentStore.savePayment(paidPayment());
        when(idGenerator.nextId()).thenReturn(3000L);
        doThrow(new IllegalStateException("audit unavailable"))
                .doNothing()
                .when(auditService)
                .recordReliable(
                        AuditService.PAYMENT_REFUNDED,
                        AuditService.OUTCOME_SUCCESS,
                        42L,
                        CUSTOMER_ROLE,
                        "PAY100",
                        null,
                        "amount=30.00,status=PARTIALLY_REFUNDED");

        PaymentRefundResponseDto completed = service.refund(
                user(), new PaymentRefundRequestDto("PAY100", new BigDecimal("30.00"), "audit"), "audit-key");
        assertThat(completed.refundedAmount()).isEqualByComparingTo(new BigDecimal("30.00"));
        assertThat(paymentStore
                        .findRefundRequest(100L, "audit-key")
                        .orElseThrow()
                        .auditIntent()
                        .state())
                .isEqualTo(RefundAuditState.PENDING);

        PaymentRefundResponseDto replay = service.refund(
                user(), new PaymentRefundRequestDto("PAY100", new BigDecimal("30.00"), "audit"), "audit-key");
        PaymentRefundResponseDto secondReplay = service.refund(
                user(), new PaymentRefundRequestDto("PAY100", new BigDecimal("30.00"), "audit"), "audit-key");

        assertThat(replay).isEqualTo(completed);
        assertThat(secondReplay).isEqualTo(completed);
        assertThat(paymentStore
                        .findRefundRequest(100L, "audit-key")
                        .orElseThrow()
                        .auditIntent()
                        .state())
                .isEqualTo(RefundAuditState.DELIVERED);
        assertThat(paymentGateway.refundRequests).hasSize(1);
        verify(auditService, times(2))
                .recordReliable(
                        AuditService.PAYMENT_REFUNDED,
                        AuditService.OUTCOME_SUCCESS,
                        42L,
                        CUSTOMER_ROLE,
                        "PAY100",
                        null,
                        "amount=30.00,status=PARTIALLY_REFUNDED");
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
        saveQueryReadyPayment(
                pendingPayment().withProviderTradeNo("wx-prepay-1", LocalDateTime.parse("2026-07-04T08:00:00")));
        paymentGateway.queryResult =
                new PaymentGatewayResult(PaymentStatus.PAID, "wx-trade-1", null, new BigDecimal("100.00"));
        when(idGenerator.nextId()).thenReturn(5000L);
        when(orderStore.transitionStatus(10L, OrderStatus.PENDING_PAYMENT.label(), OrderStatus.PAID.label(), null))
                .thenReturn(1);

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
        assertThat(paymentStore.queryAttempt("PAY100").nextReadyAt()).isNull();
        verify(orderStore).transitionStatus(10L, OrderStatus.PENDING_PAYMENT.label(), OrderStatus.PAID.label(), null);
    }

    @Test
    void scheduledTimedOutQueryDiscoversTenantTwoAndRestoresContext() {
        recoveryTenantIds = List.of(2L);
        PaymentOrder tenantTwoPayment =
                pendingPayment().withProviderTradeNo("wx-prepay-1", LocalDateTime.parse("2026-07-04T08:00:00"));
        saveQueryReadyPayment(tenantTwoPayment);
        paymentStore.queryPaymentsByTenant.put(2L, List.of(tenantTwoPayment));
        TenantContext.setTenantId(77L);

        try {
            service.queryTimedOutPaymentsScheduled();

            assertThat(paymentGateway.queryRequests).containsExactly("PAY100");
            assertThat(TenantContext.currentTenantId()).contains(77L);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void scheduledTimedOutQueryProcessesTenantAfterAFullFirstPage() {
        SequencedRecoveryTenantSource tenantSource =
                new SequencedRecoveryTenantSource(List.of(tenantIds(1L, 100L), List.of(101L)));
        recoveryTenantSource = tenantSource;
        service = newService(FIXED_CLOCK);
        PaymentOrder tenantOneHundredOnePayment =
                pendingPayment().withProviderTradeNo("wx-prepay-1", LocalDateTime.parse("2026-07-04T08:00:00"));
        saveQueryReadyPayment(tenantOneHundredOnePayment);
        paymentStore.queryPaymentsByTenant.put(101L, List.of(tenantOneHundredOnePayment));
        TenantContext.setTenantId(77L);

        try {
            service.queryTimedOutPaymentsScheduled();

            assertThat(paymentGateway.queryRequests).containsExactly("PAY100");
            assertThat(TenantContext.currentTenantId()).contains(77L);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void concurrentTimedOutQueriesUseOneProviderQueryWhileTheFirstLeaseIsActive() throws Exception {
        saveQueryReadyPayment(
                pendingPayment().withProviderTradeNo("wx-prepay-1", LocalDateTime.parse("2026-07-04T08:00:00")));
        paymentGateway.blockFirstQuery();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Integer> firstWorker = executor.submit(service::queryTimedOutPayments);
            paymentGateway.awaitFirstQuery();
            Future<Integer> secondWorker = executor.submit(service::queryTimedOutPayments);

            assertThat(secondWorker.get(5, TimeUnit.SECONDS)).isZero();
            assertThat(paymentGateway.queryRequests).containsExactly("PAY100");
            paymentGateway.releaseFirstQuery();
            assertThat(firstWorker.get(5, TimeUnit.SECONDS)).isZero();
        } finally {
            paymentGateway.releaseFirstQuery();
            executor.shutdownNow();
        }
    }

    @Test
    void staleQueryClaimDoesNotCallProviderAfterTakeover() throws Exception {
        MutableClock clock = new MutableClock(FIXED_CLOCK.instant());
        PaymentApplicationService fencedService = newService(clock);
        saveQueryReadyPayment(
                pendingPayment().withProviderTradeNo("wx-prepay-1", LocalDateTime.parse("2026-07-04T08:00:00")));
        paymentGateway.queryResult =
                new PaymentGatewayResult(PaymentStatus.PAID, "stale-query", null, new BigDecimal("100.00"));
        transactions.pauseAfterCommit(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<Integer> staleWorker = executor.submit(fencedService::queryTimedOutPayments);
            transactions.awaitPausedCommit();
            PaymentQueryAttempt initialAttempt = paymentStore.queryAttempt("PAY100");
            clock.advance(Duration.ofMinutes(3));
            PaymentQueryAttempt newerAttempt = paymentStore
                    .withLockedPayment("PAY100", payment -> {
                        PaymentStore.PaymentIntent latest = paymentStore
                                .findPaymentIntentByPaymentNo(payment.paymentNo())
                                .orElseThrow();
                        return paymentStore
                                .savePaymentQueryAttempt(
                                        payment,
                                        latest.queryAttempt()
                                                .claim(
                                                        LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC),
                                                        Duration.ofMinutes(2)))
                                .queryAttempt();
                    })
                    .orElseThrow();
            transactions.releasePausedCommit();
            int handled = staleWorker.get(5, TimeUnit.SECONDS);

            SoftAssertions.assertSoftly(softly -> {
                softly.assertThat(initialAttempt.attemptToken()).isEqualTo(1);
                softly.assertThat(newerAttempt.attemptToken()).isEqualTo(2);
                softly.assertThat(paymentGateway.queryRequests).isEmpty();
                softly.assertThat(paymentStore.queryAttempt("PAY100")).isEqualTo(newerAttempt);
                softly.assertThat(paymentStore
                                .findByPaymentNo("PAY100")
                                .orElseThrow()
                                .status())
                        .isEqualTo(PaymentStatus.PENDING);
                softly.assertThat(handled).isZero();
            });
        } finally {
            transactions.releasePausedCommit();
            executor.shutdownNow();
        }
    }

    @Test
    void pendingQuerySchedulesLaterWithoutReopeningCreateOperation() {
        saveQueryReadyPayment(
                pendingPayment().withProviderTradeNo("wx-prepay-1", LocalDateTime.parse("2026-07-04T08:00:00")));

        int handled = service.queryTimedOutPayments();

        assertThat(handled).isZero();
        assertThat(paymentGateway.queryRequests).containsExactly("PAY100");
        assertThat(paymentStore.queryAttempt("PAY100"))
                .isEqualTo(new PaymentQueryAttempt(1, null, LocalDateTime.parse("2026-07-04T08:30:30")));
        assertThat(paymentStore.paymentOperation("PAY100").state()).isEqualTo(PaymentOperationState.COMPLETED);
        assertThat(paymentGateway.createRequests).isEmpty();
    }

    @Test
    void queryExceptionSchedulesLaterWithoutReopeningCreateOperation() {
        saveQueryReadyPayment(
                pendingPayment().withProviderTradeNo("wx-prepay-1", LocalDateTime.parse("2026-07-04T08:00:00")));
        paymentGateway.queryFailure = new IllegalStateException("provider query unavailable");

        Throwable thrown = catchThrowable(() -> service.queryTimedOutPayments());

        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(thrown).isNull();
            softly.assertThat(paymentGateway.queryRequests).containsExactly("PAY100");
            softly.assertThat(paymentStore.queryAttempt("PAY100"))
                    .isEqualTo(new PaymentQueryAttempt(1, null, LocalDateTime.parse("2026-07-04T08:30:30")));
            softly.assertThat(paymentStore.paymentOperation("PAY100").state())
                    .isEqualTo(PaymentOperationState.COMPLETED);
            softly.assertThat(paymentGateway.createRequests).isEmpty();
        });
    }

    @Test
    void stalePaidQueryResultCannotOverwriteANewerAttempt() throws Exception {
        assertStaleQueryResultCannotOverwriteANewerAttempt(
                new PaymentGatewayResult(PaymentStatus.PAID, "stale-paid", null, new BigDecimal("100.00")), null);
    }

    @Test
    void staleFailedQueryResultCannotOverwriteANewerAttempt() throws Exception {
        assertStaleQueryResultCannotOverwriteANewerAttempt(
                new PaymentGatewayResult(PaymentStatus.FAILED, null, null, BigDecimal.ZERO), null);
    }

    @Test
    void stalePendingQueryResultCannotOverwriteANewerAttempt() throws Exception {
        assertStaleQueryResultCannotOverwriteANewerAttempt(
                new PaymentGatewayResult(PaymentStatus.PENDING, null, null, BigDecimal.ZERO), null);
    }

    @Test
    void staleQueryExceptionCannotOverwriteANewerAttempt() throws Exception {
        assertStaleQueryResultCannotOverwriteANewerAttempt(null, new IllegalStateException("stale query failure"));
    }

    private void assertStaleQueryResultCannotOverwriteANewerAttempt(
            PaymentGatewayResult result, RuntimeException queryFailure) throws Exception {
        PaymentOrder payment =
                pendingPayment().withProviderTradeNo("wx-prepay-1", LocalDateTime.parse("2026-07-04T08:00:00"));
        saveQueryReadyPayment(payment);
        paymentGateway.queryResult = result;
        paymentGateway.queryFailure = queryFailure;
        paymentGateway.blockFirstQuery();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        PaymentQueryAttempt newerAttempt = new PaymentQueryAttempt(
                2, LocalDateTime.parse("2026-07-04T08:35:00"), LocalDateTime.parse("2026-07-04T08:00:00"));

        try {
            Future<Integer> staleWorker = executor.submit(service::queryTimedOutPayments);
            paymentGateway.awaitFirstQuery();
            PaymentQueryAttempt observedClaim = paymentStore.queryAttempt("PAY100");
            paymentStore.forceQueryAttempt("PAY100", newerAttempt);
            paymentGateway.releaseFirstQuery();
            Throwable workerFailure = null;
            try {
                staleWorker.get(5, TimeUnit.SECONDS);
            } catch (ExecutionException exception) {
                workerFailure = exception.getCause();
            }

            Throwable finalWorkerFailure = workerFailure;
            SoftAssertions.assertSoftly(softly -> {
                softly.assertThat(observedClaim.attemptToken())
                        .as("claimed query token")
                        .isEqualTo(1);
                softly.assertThat(finalWorkerFailure).as("stale worker failure").isNull();
                softly.assertThat(paymentStore
                                .findByPaymentNo("PAY100")
                                .orElseThrow()
                                .status())
                        .isEqualTo(PaymentStatus.PENDING);
                softly.assertThat(paymentStore.queryAttempt("PAY100")).isEqualTo(newerAttempt);
                softly.assertThat(paymentStore.paymentOperation("PAY100").state())
                        .isEqualTo(PaymentOperationState.COMPLETED);
                softly.assertThat(paymentStore.ledgers).isEmpty();
                softly.assertThat(paymentGateway.queryRequests).containsExactly("PAY100");
            });
        } finally {
            paymentGateway.releaseFirstQuery();
            executor.shutdownNow();
        }
    }

    private void saveQueryReadyPayment(PaymentOrder payment) {
        PaymentOperationAttempt completed = new PaymentOperationAttempt(
                PaymentOperationState.COMPLETED, 1, null, PaymentFailureClassification.NONE);
        PaymentRequestFingerprint fingerprint =
                PaymentRequestFingerprint.of(payment.orderId(), payment.method(), payment.amount(), "CNY");
        paymentStore.savePayment(
                payment,
                fingerprint.value(),
                completed,
                payment.paymentNo(),
                PaymentResponseSnapshot.capture(payment, "/payments/" + payment.paymentNo()));
        paymentStore.savePaymentQueryAttempt(
                payment, PaymentQueryAttempt.readyAt(LocalDateTime.parse("2026-07-04T08:00:00")));
    }

    private static SessionUser user() {
        return new SessionUser(42L, "USER");
    }

    private static List<Long> tenantIds(long first, long last) {
        return LongStream.rangeClosed(first, last).boxed().toList();
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
        return pendingPayment(100L, 10L, "pay-key");
    }

    private static PaymentOrder pendingPayment(Long id, Long orderId, String idempotencyKey) {
        return new PaymentOrder(
                id,
                "PAY" + id,
                orderId,
                42L,
                PaymentMethod.WECHAT,
                new BigDecimal("100.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                PaymentStatus.PENDING,
                idempotencyKey,
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

    private static PaymentLedgerEntry refundLedger(Long id, PaymentLedgerStatus status, String requestKey) {
        return new PaymentLedgerEntry(
                id,
                100L,
                10L,
                42L,
                PaymentLedgerType.REFUND,
                new BigDecimal("30.00"),
                status,
                requestKey,
                null,
                LocalDateTime.parse("2026-07-04T08:20:00"));
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

    private static final class SequencedRecoveryTenantSource implements PaymentRecoveryTenantSource {
        private final List<List<Long>> pages;
        private final AtomicInteger calls = new AtomicInteger();
        private final List<Long> cursors = new ArrayList<>();

        private SequencedRecoveryTenantSource(List<List<Long>> pages) {
            this.pages = pages;
        }

        @Override
        public List<Long> findTenantIdsReadyForRecovery(LocalDateTime cutoff, long afterTenantId, int limit) {
            cursors.add(afterTenantId);
            int page = calls.getAndIncrement();
            return page < pages.size() ? pages.get(page) : List.of();
        }

        private int calls() {
            return calls.get();
        }

        private List<Long> cursors() {
            return List.copyOf(cursors);
        }
    }

    private static final class RecordingPaymentGateway implements PaymentGateway {
        private PaymentGatewayResult queryResult =
                new PaymentGatewayResult(PaymentStatus.PENDING, null, null, BigDecimal.ZERO);
        private final List<String> refundRequests = new CopyOnWriteArrayList<>();
        private final List<String> createRequests = new CopyOnWriteArrayList<>();
        private final List<BigDecimal> refundAmounts = new CopyOnWriteArrayList<>();
        private final List<String> queryRequests = new CopyOnWriteArrayList<>();
        private final List<Boolean> queryTransactionStates = new CopyOnWriteArrayList<>();
        private final AtomicInteger queryCalls = new AtomicInteger();
        private RuntimeException createFailure;
        private RuntimeException refundFailure;
        private RuntimeException queryFailure;
        private Consumer<PaymentOrder> beforeCreate;
        private Runnable afterNextCreate;
        private Runnable afterNextRefund;
        private List<String> boundaryEvents;
        private final AtomicInteger coordinatedCreateCalls = new AtomicInteger();
        private CountDownLatch firstCreateEntered;
        private CountDownLatch releaseFirstCreate;
        private CountDownLatch secondCreateEntered;
        private CountDownLatch releaseSecondCreate;
        private CountDownLatch firstQueryEntered;
        private CountDownLatch releaseFirstQuery;
        private RuntimeException firstCoordinatedCreateFailure;

        @Override
        public PaymentGatewayResult create(PaymentOrder payment, String merchantToken) {
            recordBoundary("gateway-create");
            createRequests.add(merchantToken);
            if (beforeCreate != null) {
                beforeCreate.accept(payment);
            }
            coordinateCreateWorker();
            if (createFailure != null) {
                throw createFailure;
            }
            Runnable afterCreate = afterNextCreate;
            afterNextCreate = null;
            if (afterCreate != null) {
                afterCreate.run();
            }
            return new PaymentGatewayResult(
                    PaymentStatus.PENDING,
                    "SANDBOX-" + payment.paymentNo(),
                    "/sandbox/payments/" + payment.paymentNo(),
                    payment.amount());
        }

        private void coordinateTwoCreateWorkers(RuntimeException firstFailure) {
            firstCreateEntered = new CountDownLatch(1);
            releaseFirstCreate = new CountDownLatch(1);
            secondCreateEntered = new CountDownLatch(1);
            releaseSecondCreate = new CountDownLatch(1);
            firstCoordinatedCreateFailure = firstFailure;
        }

        private void blockFirstCreate() {
            firstCreateEntered = new CountDownLatch(1);
            releaseFirstCreate = new CountDownLatch(1);
            secondCreateEntered = null;
            releaseSecondCreate = null;
            firstCoordinatedCreateFailure = null;
        }

        private void awaitFirstCreate() {
            awaitLatch(firstCreateEntered, "first provider create");
        }

        private void awaitSecondCreate() {
            awaitLatch(secondCreateEntered, "second provider create");
        }

        private void releaseFirstCreate() {
            releaseFirstCreate.countDown();
        }

        private void releaseSecondCreate() {
            releaseSecondCreate.countDown();
        }

        private void coordinateCreateWorker() {
            if (firstCreateEntered == null) {
                return;
            }
            int call = coordinatedCreateCalls.incrementAndGet();
            if (call == 1) {
                firstCreateEntered.countDown();
                awaitLatch(releaseFirstCreate, "release first provider create");
                if (firstCoordinatedCreateFailure != null) {
                    throw firstCoordinatedCreateFailure;
                }
                return;
            }
            if (call == 2 && secondCreateEntered != null) {
                secondCreateEntered.countDown();
                awaitLatch(releaseSecondCreate, "release second provider create");
            }
        }

        @Override
        public PaymentGatewayResult query(PaymentOrder payment) {
            int queryCall = queryCalls.incrementAndGet();
            queryRequests.add(payment.paymentNo());
            queryTransactionStates.add(TransactionSynchronizationManager.isActualTransactionActive());
            if (firstQueryEntered != null && queryCall == 1) {
                firstQueryEntered.countDown();
                awaitLatch(releaseFirstQuery, "release first provider query");
            }
            if (queryFailure != null) {
                throw queryFailure;
            }
            return queryResult;
        }

        private void blockFirstQuery() {
            firstQueryEntered = new CountDownLatch(1);
            releaseFirstQuery = new CountDownLatch(1);
        }

        private void awaitFirstQuery() {
            awaitLatch(firstQueryEntered, "first provider query");
        }

        private void releaseFirstQuery() {
            if (releaseFirstQuery != null) {
                releaseFirstQuery.countDown();
            }
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

    private static final class MutableClock extends Clock {
        private volatile Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        private void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return zone.equals(getZone()) ? this : Clock.fixed(current, zone);
        }

        @Override
        public Instant instant() {
            return current;
        }
    }

    private static void awaitLatch(CountDownLatch latch, String description) {
        try {
            if (latch == null || !latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for " + description);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for " + description, exception);
        }
    }

    private static Throwable awaitWorkerFailure(Future<?> worker) throws Exception {
        try {
            worker.get(5, TimeUnit.SECONDS);
            return null;
        } catch (ExecutionException exception) {
            return exception.getCause();
        }
    }

    private static boolean isSuccessfulOrConflict(Throwable failure) {
        return failure == null
                || (failure instanceof BusinessException businessException
                        && ErrorCode.CONFLICT.equals(businessException.errorCode()));
    }

    private static final class InMemoryPaymentStore implements PaymentStore {
        private final Map<Long, PaymentOrder> payments = new ConcurrentHashMap<>();
        private final Map<Long, String> paymentFingerprints = new ConcurrentHashMap<>();
        private final Map<Long, PaymentResponseSnapshot> paymentSnapshots = new ConcurrentHashMap<>();
        private final Map<Long, PaymentOperationAttempt> paymentOperations = new ConcurrentHashMap<>();
        private final Map<Long, String> paymentMerchantTokens = new ConcurrentHashMap<>();
        private final Map<Long, PaymentQueryAttempt> paymentQueryAttempts = new ConcurrentHashMap<>();
        private final List<PaymentLedgerEntry> ledgers = new CopyOnWriteArrayList<>();
        private final Map<Long, String> ledgerFingerprints = new ConcurrentHashMap<>();
        private final Map<Long, RefundResponseSnapshot> refundSnapshots = new ConcurrentHashMap<>();
        private final Map<Long, PaymentOperationAttempt> refundOperations = new ConcurrentHashMap<>();
        private final Map<Long, String> refundMerchantTokens = new ConcurrentHashMap<>();
        private final Map<Long, RefundAuditIntent> refundAuditIntents = new ConcurrentHashMap<>();
        private final List<PaymentReconciliationReport> reports = new ArrayList<>();
        private final AtomicReference<Runnable> afterIntentRead = new AtomicReference<>();
        private volatile CountDownLatch paymentReads;
        private volatile CountDownLatch intentReads;
        private List<String> boundaryEvents;
        private volatile boolean failNextPaymentCompletion;
        private volatile boolean failNextPaymentUpdate;
        private volatile DataIntegrityViolationException nextPaymentReservationFailure;
        private volatile DataIntegrityViolationException nextRefundReservationFailure;
        private volatile boolean requirePaymentLockForCompletion;
        private final Set<Long> failingRecoveryTenantIds = ConcurrentHashMap.newKeySet();
        private final List<Long> recoveryTenantQueries = new CopyOnWriteArrayList<>();
        private final Map<Long, List<PaymentOrder>> queryPaymentsByTenant = new ConcurrentHashMap<>();
        private final ThreadLocal<Boolean> paymentLockHeld = ThreadLocal.withInitial(() -> false);

        private void synchronizeNextPaymentReads(int parties) {
            paymentReads = new CountDownLatch(parties);
        }

        private void synchronizeNextIntentReads(int parties) {
            intentReads = new CountDownLatch(parties);
        }

        private void afterNextIntentRead(Runnable action) {
            afterIntentRead.set(action);
        }

        private void saveLegacyPaymentIntent(PaymentOrder payment, String requestFingerprint) {
            payments.put(payment.id(), payment);
            paymentFingerprints.put(payment.id(), requestFingerprint);
            paymentOperations.put(payment.id(), PaymentOperationAttempt.legacy());
            paymentQueryAttempts.put(payment.id(), PaymentQueryAttempt.notScheduled());
        }

        private void saveLegacyRefund(PaymentLedgerEntry ledger) {
            ledgers.add(ledger);
            refundOperations.put(ledger.id(), PaymentOperationAttempt.legacy());
            refundAuditIntents.put(ledger.id(), RefundAuditIntent.legacy());
        }

        private PaymentQueryAttempt queryAttempt(String paymentNo) {
            PaymentOrder payment = findByPaymentNo(paymentNo).orElseThrow();
            return paymentQueryAttempts.getOrDefault(payment.id(), PaymentQueryAttempt.notScheduled());
        }

        private PaymentOperationAttempt paymentOperation(String paymentNo) {
            PaymentOrder payment = findByPaymentNo(paymentNo).orElseThrow();
            return paymentOperations.get(payment.id());
        }

        private void forceQueryAttempt(String paymentNo, PaymentQueryAttempt queryAttempt) {
            PaymentOrder payment = findByPaymentNo(paymentNo).orElseThrow();
            paymentQueryAttempts.put(payment.id(), queryAttempt);
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
        public Optional<PaymentOrder> findById(Long paymentId) {
            return Optional.ofNullable(payments.get(paymentId));
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
                            paymentOperations.get(payment.id()),
                            paymentMerchantTokens.get(payment.id()),
                            paymentSnapshots.get(payment.id()),
                            paymentQueryAttempts.getOrDefault(payment.id(), PaymentQueryAttempt.notScheduled())));
            Runnable action = afterIntentRead.getAndSet(null);
            if (action != null) {
                action.run();
            }
            awaitConcurrentReads(intentReads, "payment intent");
            intentReads = null;
            return result;
        }

        @Override
        public Optional<PaymentIntent> findPaymentIntentByPaymentNo(String paymentNo) {
            return payments.values().stream()
                    .filter(payment -> payment.paymentNo().equals(paymentNo))
                    .findFirst()
                    .flatMap(payment -> findByUserIdAndIdempotencyKey(payment.userId(), payment.idempotencyKey()));
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
                            paymentOperations.get(payment.id()),
                            paymentMerchantTokens.get(payment.id()),
                            paymentSnapshots.get(payment.id()),
                            paymentQueryAttempts.getOrDefault(payment.id(), PaymentQueryAttempt.notScheduled())));
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
                            refundOperations.get(ledger.id()),
                            refundMerchantTokens.get(ledger.id()),
                            refundSnapshots.get(ledger.id()),
                            refundAuditIntents.getOrDefault(ledger.id(), RefundAuditIntent.legacy())));
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
            if (!PaymentStatus.PENDING.equals(payment.status())) {
                paymentQueryAttempts.computeIfPresent(payment.id(), (ignored, attempt) -> attempt.stop());
            }
            return payment;
        }

        @Override
        public synchronized PaymentIntent savePayment(
                PaymentOrder payment,
                String requestFingerprint,
                PaymentOperationAttempt operation,
                String merchantToken,
                PaymentResponseSnapshot responseSnapshot) {
            recordBoundary(responseSnapshot == null ? "payment-reservation" : "payment-completion");
            if (responseSnapshot == null && !payments.containsKey(payment.id())) {
                if (nextPaymentReservationFailure != null) {
                    DataIntegrityViolationException failure = nextPaymentReservationFailure;
                    nextPaymentReservationFailure = null;
                    throw failure;
                }
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
                    String constraint = duplicateKey ? "uk_payment_order_user_key" : "uk_payment_order_active_order";
                    throw new DataIntegrityViolationException("Duplicate entry for key '" + constraint + "'");
                }
            } else if (requirePaymentLockForCompletion && !paymentLockHeld.get()) {
                throw new LocalCompletionFailure();
            } else if (failNextPaymentCompletion) {
                failNextPaymentCompletion = false;
                throw new LocalCompletionFailure();
            }
            payments.put(payment.id(), payment);
            paymentFingerprints.put(payment.id(), requestFingerprint);
            paymentOperations.put(payment.id(), operation);
            paymentMerchantTokens.put(payment.id(), merchantToken);
            paymentQueryAttempts.putIfAbsent(payment.id(), PaymentQueryAttempt.notScheduled());
            if (responseSnapshot != null) {
                paymentSnapshots.put(payment.id(), responseSnapshot);
            }
            return new PaymentIntent(
                    payment,
                    requestFingerprint,
                    operation,
                    merchantToken,
                    paymentSnapshots.get(payment.id()),
                    paymentQueryAttempts.get(payment.id()));
        }

        @Override
        public synchronized PaymentIntent savePaymentQueryAttempt(
                PaymentOrder payment, PaymentQueryAttempt queryAttempt) {
            paymentQueryAttempts.put(payment.id(), queryAttempt);
            return findPaymentIntentByPaymentNo(payment.paymentNo()).orElseThrow();
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
                PaymentOperationAttempt operation,
                String merchantToken,
                RefundResponseSnapshot responseSnapshot,
                RefundAuditIntent auditIntent) {
            recordBoundary(
                    RefundAuditState.DELIVERED.equals(auditIntent.state())
                            ? "refund-audit-delivery"
                            : responseSnapshot == null ? "refund-reservation" : "refund-completion");
            if (PaymentOperationState.RESERVED.equals(operation.state())
                    && ledgers.stream().noneMatch(existing -> existing.id().equals(ledger.id()))
                    && nextRefundReservationFailure != null) {
                DataIntegrityViolationException failure = nextRefundReservationFailure;
                nextRefundReservationFailure = null;
                throw failure;
            }
            if (PaymentOperationState.RESERVED.equals(operation.state())
                    && ledgers.stream().noneMatch(existing -> existing.id().equals(ledger.id()))
                    && ledgers.stream()
                            .anyMatch(existing -> existing.paymentId().equals(ledger.paymentId())
                                    && existing.type().equals(ledger.type())
                                    && existing.requestKey().equals(ledger.requestKey()))) {
                throw new DataIntegrityViolationException("Duplicate entry for key 'uk_payment_ledger_request'");
            }
            ledgers.removeIf(existing -> existing.id().equals(ledger.id()));
            ledgers.add(ledger);
            ledgerFingerprints.put(ledger.id(), requestFingerprint);
            refundOperations.put(ledger.id(), operation);
            refundMerchantTokens.put(ledger.id(), merchantToken);
            refundAuditIntents.put(ledger.id(), auditIntent);
            if (responseSnapshot != null) {
                refundSnapshots.put(ledger.id(), responseSnapshot);
            }
            return new RefundRequest(
                    ledger,
                    requestFingerprint,
                    operation,
                    merchantToken,
                    refundSnapshots.get(ledger.id()),
                    auditIntent);
        }

        @Override
        public List<PaymentIntent> findExpiredPaymentOperations(LocalDateTime cutoff, int limit) {
            long tenantId = TenantContext.currentTenantIdOrDefault();
            recoveryTenantQueries.add(tenantId);
            if (failingRecoveryTenantIds.contains(tenantId)) {
                throw new IllegalStateException("tenant recovery failed");
            }
            return payments.values().stream()
                    .map(payment -> findByUserIdAndIdempotencyKey(payment.userId(), payment.idempotencyKey()))
                    .flatMap(Optional::stream)
                    .filter(intent -> intent.operation() != null)
                    .filter(intent -> intent.operation().isExpired(cutoff))
                    .sorted(Comparator.comparing(intent -> intent.payment().id()))
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<RefundRequest> findExpiredRefundOperations(LocalDateTime cutoff, int limit) {
            return ledgers.stream()
                    .filter(ledger -> PaymentLedgerType.REFUND.equals(ledger.type()))
                    .map(ledger -> findRefundRequest(ledger.paymentId(), ledger.requestKey()))
                    .flatMap(Optional::stream)
                    .filter(refund -> refund.operation().isExpired(cutoff))
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<RefundRequest> findPendingRefundAudits(int limit) {
            return ledgers.stream()
                    .filter(ledger -> PaymentLedgerType.REFUND.equals(ledger.type()))
                    .map(ledger -> findRefundRequest(ledger.paymentId(), ledger.requestKey()))
                    .flatMap(Optional::stream)
                    .filter(refund ->
                            RefundAuditState.PENDING.equals(refund.auditIntent().state()))
                    .limit(limit)
                    .toList();
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
            var candidates = queryPaymentsByTenant.isEmpty()
                    ? payments.values().stream()
                    : queryPaymentsByTenant.getOrDefault(TenantContext.currentTenantIdOrDefault(), List.of()).stream();
            return candidates
                    .filter(payment -> payment.status().equals(PaymentStatus.PENDING))
                    .filter(payment -> payment.createTime().isBefore(cutoff))
                    .sorted(Comparator.comparing(PaymentOrder::createTime))
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<PaymentIntent> findPaymentsReadyForQuery(LocalDateTime readyAt, int limit) {
            var candidates = queryPaymentsByTenant.isEmpty()
                    ? payments.values().stream()
                    : queryPaymentsByTenant.getOrDefault(TenantContext.currentTenantIdOrDefault(), List.of()).stream();
            return candidates
                    .map(payment -> findPaymentIntentByPaymentNo(payment.paymentNo()))
                    .flatMap(Optional::stream)
                    .filter(intent ->
                            PaymentStatus.PENDING.equals(intent.payment().status()))
                    .filter(intent -> List.of(
                                    PaymentOperationState.COMPLETED, PaymentOperationState.LEGACY_UNREPLAYABLE)
                            .contains(intent.operationState()))
                    .filter(intent -> intent.queryAttempt().isClaimableAt(readyAt))
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

    private static final class RecordingTransactions implements PaymentTransactions {
        private final List<String> events = new CopyOnWriteArrayList<>();
        private final AtomicInteger committedTransactions = new AtomicInteger();
        private volatile int pauseAfterCommit = -1;
        private volatile CountDownLatch pausedCommit;
        private volatile CountDownLatch releaseCommit;

        private void pauseAfterCommit(int commitNumber) {
            pauseAfterCommit = commitNumber;
            pausedCommit = new CountDownLatch(1);
            releaseCommit = new CountDownLatch(1);
        }

        private void awaitPausedCommit() {
            awaitLatch(pausedCommit, "committed payment transaction pause");
        }

        private void releasePausedCommit() {
            CountDownLatch release = releaseCommit;
            if (release != null) {
                release.countDown();
            }
        }

        @Override
        public <T> T execute(java.util.function.Supplier<T> action) {
            events.add("transaction-begin");
            try {
                T result = action.get();
                events.add("transaction-commit");
                int committed = committedTransactions.incrementAndGet();
                if (committed == pauseAfterCommit) {
                    pausedCommit.countDown();
                    awaitLatch(releaseCommit, "release committed payment transaction");
                }
                return result;
            } catch (RuntimeException exception) {
                events.add("transaction-rollback");
                throw exception;
            }
        }
    }

    private static final class LocalCompletionFailure extends RuntimeException {}
}
