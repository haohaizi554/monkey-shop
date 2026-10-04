package com.example.monkey.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.order.domain.OrderStatus;
import com.example.monkey.order.domain.OrderStore;
import com.example.monkey.payment.application.dto.PaymentReconciliationRequestDto;
import com.example.monkey.payment.application.dto.PaymentRefundRequestDto;
import com.example.monkey.payment.application.dto.PaymentReconciliationResponseDto;
import com.example.monkey.payment.application.dto.ReconciliationLineDto;
import com.example.monkey.payment.domain.PaymentCallbackReplayGuard;
import com.example.monkey.payment.domain.PaymentGateway;
import com.example.monkey.payment.domain.PaymentGatewayException;
import com.example.monkey.payment.domain.PaymentLedgerEntry;
import com.example.monkey.payment.domain.PaymentLedgerStatus;
import com.example.monkey.payment.domain.PaymentLedgerType;
import com.example.monkey.payment.domain.PaymentMethod;
import com.example.monkey.payment.domain.PaymentOperationAttempt;
import com.example.monkey.payment.domain.PaymentOperationState;
import com.example.monkey.payment.domain.PaymentOrder;
import com.example.monkey.payment.domain.PaymentReconciliationReport;
import com.example.monkey.payment.domain.PaymentStatus;
import com.example.monkey.payment.domain.PaymentStore;
import com.example.monkey.payment.domain.PaymentTransitionPolicy;
import com.example.monkey.payment.domain.PaymentTransitionResolver;
import com.example.monkey.payment.domain.RefundAuditIntent;
import com.example.monkey.payment.domain.RefundResponseSnapshot;
import com.example.monkey.shared.application.observability.AuditService;
import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.shared.domain.id.IdGenerator;
import com.example.monkey.shared.domain.inventory.InventoryReservationLifecycle;
import com.example.monkey.user.domain.UserAccountStore;
import com.example.monkey.user.domain.UserMfaVerifier;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PaymentReconciliationRefundRaceTest {

    private static final LocalDate REPORT_DATE = LocalDate.of(2026, 7, 4);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-04T08:30:00Z"), ZoneOffset.UTC);
    private static final SessionUser CUSTOMER = new SessionUser(42L, "CUSTOMER");
    private static final PaymentTransitionResolver TRANSITIONS = (currentStatus, event) ->
            PaymentTransitionPolicy.nextStatus(currentStatus, event)
                    .orElseThrow(() -> new BusinessException(
                            ErrorCode.CONFLICT, PaymentTransitionPolicy.STATUS_TRANSITION_NOT_ALLOWED));

    private PaymentStore paymentStore;
    private PaymentGateway paymentGateway;
    private OrderStore orderStore;
    private IdGenerator idGenerator;
    private RaceStore raceStore;
    private PaymentApplicationService service;

    @BeforeEach
    void setUp() {
        paymentStore = mock(PaymentStore.class);
        paymentGateway = mock(PaymentGateway.class);
        orderStore = mock(OrderStore.class);
        raceStore = new RaceStore();
        idGenerator = mock(IdGenerator.class);
        when(orderStore.findById(10L)).thenReturn(Optional.of(orderWithStatus(OrderStatus.RETURN_SHIPPING)));

        when(paymentStore.findPaidByProviderAndDate(any(PaymentMethod.class), any(LocalDate.class)))
                .thenAnswer(invocation -> raceStore.paidCandidates());
        when(paymentStore.withLockedPayment(anyString(), any()))
                .thenAnswer(invocation -> raceStore.withLockedPayment(invocation.getArgument(0), invocation.getArgument(1)));
        when(paymentStore.sumAcceptedRefundAmount(anyLong()))
                .thenAnswer(invocation -> raceStore.sumAcceptedRefundAmount(invocation.getArgument(0)));
        when(paymentStore.findRefundRequest(anyLong(), anyString()))
                .thenAnswer(invocation -> raceStore.findRefundRequest(
                        invocation.getArgument(0), invocation.getArgument(1)));
        when(paymentStore.savePayment(any(PaymentOrder.class)))
                .thenAnswer(invocation -> raceStore.savePayment(invocation.getArgument(0)));
        when(paymentStore.saveReport(any(PaymentReconciliationReport.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        doAnswer(invocation -> raceStore.saveRefundLedger(
                        invocation.getArgument(0),
                        invocation.getArgument(1),
                        invocation.getArgument(2),
                        invocation.getArgument(3),
                        invocation.getArgument(4),
                        invocation.getArgument(5)))
                .when(paymentStore)
                .saveLedger(
                        any(PaymentLedgerEntry.class),
                        anyString(),
                        any(PaymentOperationAttempt.class),
                        anyString(),
                        nullable(RefundResponseSnapshot.class),
                        any(RefundAuditIntent.class));

        service = new PaymentApplicationService(
                paymentStore,
                paymentGateway,
                mock(PaymentCallbackReplayGuard.class),
                TRANSITIONS,
                orderStore,
                mock(InventoryReservationLifecycle.class),
                mock(UserAccountStore.class),
                mock(UserMfaVerifier.class),
                idGenerator,
                mock(AuditService.class),
                new DirectPaymentTransactions(),
                CLOCK,
                Duration.ofHours(24),
                Duration.ofMinutes(5),
                BigDecimal.valueOf(5000),
                "callback-secret");
    }

    @Test
    void reconciliationKeepsPaymentStateWhenAcceptedRefundLedgerExists() {
        raceStore.addAcceptedRefund("existing-refund", 30L, new BigDecimal("30.00"));
        when(idGenerator.nextId()).thenReturn(9000L);

        PaymentReconciliationResponseDto response = reconcileMismatch();

        assertThat(response.status()).isEqualTo(com.example.monkey.payment.domain.ReconciliationStatus.SUSPENDED);
        assertThat(response.issueCount()).isEqualTo(1);
        assertThat(raceStore.payment().status()).isEqualTo(PaymentStatus.PAID);
        assertThat(raceStore.sumAcceptedRefundAmount(100L)).isEqualByComparingTo(new BigDecimal("30.00"));
        ArgumentCaptor<PaymentReconciliationReport> reportCaptor =
                ArgumentCaptor.forClass(PaymentReconciliationReport.class);
        verify(paymentStore).saveReport(reportCaptor.capture());
        assertThat(reportCaptor.getValue().reportPayload()).contains("platform:PAY100");
        verify(paymentStore).withLockedPayment(eq("PAY100"), any());
        verify(paymentStore).sumAcceptedRefundAmount(100L);
        verify(paymentStore, never()).savePayment(any(PaymentOrder.class));
    }

    @Test
    void refundReservationCommittedBeforeReconciliationCannotBeOverwritten() throws Exception {
        CountDownLatch candidatesRead = new CountDownLatch(1);
        CountDownLatch releaseCandidates = new CountDownLatch(1);
        raceStore.blockCandidateRead(candidatesRead, releaseCandidates);
        RuntimeException providerFailure = new IllegalStateException("provider unavailable");
        when(paymentGateway.refund(any(PaymentOrder.class), any(BigDecimal.class), anyString()))
                .thenThrow(providerFailure);
        when(idGenerator.nextId()).thenReturn(3000L, 9001L);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<PaymentReconciliationResponseDto> reconciliation;
        AtomicReference<Throwable> refundFailure = new AtomicReference<>();
        try {
            reconciliation = executor.submit(this::reconcileMismatch);
            await(candidatesRead, "reconciliation candidate read");

            try {
                service.refund(
                        CUSTOMER,
                        new PaymentRefundRequestDto("PAY100", new BigDecimal("30.00"), "return"),
                        "refund-key");
            } catch (Throwable failure) {
                refundFailure.set(failure);
            } finally {
                releaseCandidates.countDown();
            }

            reconciliation.get(5, TimeUnit.SECONDS);
        } finally {
            releaseCandidates.countDown();
            executor.shutdownNow();
        }

        assertThat(refundFailure.get()).isSameAs(providerFailure);
        assertThat(raceStore.payment().status()).isEqualTo(PaymentStatus.PAID);
        assertThat(raceStore.sumAcceptedRefundAmount(100L)).isEqualByComparingTo(new BigDecimal("30.00"));
        assertThat(raceStore.findRefundRequest(100L, "refund-key"))
                .get()
                .extracting(PaymentStore.RefundRequest::operationState)
                .isEqualTo(PaymentOperationState.RETRYABLE);
        assertThat(raceStore.savedPaymentCount()).isZero();
        verify(paymentGateway).refund(any(PaymentOrder.class), any(BigDecimal.class), eq("PAY100:refund:3000"));
    }

    @Test
    void reconciliationSuspendsBeforeRefundSoRefundCannotReserveOrCallProvider() {
        when(idGenerator.nextId()).thenReturn(9002L);

        reconcileMismatch();

        assertThat(raceStore.payment().status()).isEqualTo(PaymentStatus.SUSPENDED);
        assertThatThrownBy(() -> service.refund(
                        CUSTOMER,
                        new PaymentRefundRequestDto("PAY100", new BigDecimal("30.00"), "suspended"),
                        "refund-after-reconciliation"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        assertThat(raceStore.ledgerEntries()).isEmpty();
        verify(paymentGateway, never()).refund(any(PaymentOrder.class), any(BigDecimal.class), anyString());
        verify(paymentStore, never())
                .saveLedger(
                        any(PaymentLedgerEntry.class),
                        anyString(),
                        any(PaymentOperationAttempt.class),
                        anyString(),
                        nullable(RefundResponseSnapshot.class),
                        any(RefundAuditIntent.class));
    }

    @Test
    void terminalRefundFailureReleasesAcceptedAmountForLaterReconciliation() {
        PaymentGatewayException rejected = PaymentGatewayException.rejected("REFUND_DECLINED", "provider detail");
        when(paymentGateway.refund(any(PaymentOrder.class), any(BigDecimal.class), anyString()))
                .thenThrow(rejected);
        when(idGenerator.nextId()).thenReturn(3004L, 9004L);

        assertThatThrownBy(() -> service.refund(
                        CUSTOMER,
                        new PaymentRefundRequestDto("PAY100", new BigDecimal("30.00"), "declined"),
                        "terminal-refund"))
                .isInstanceOf(PaymentGatewayException.class);

        assertThat(raceStore.sumAcceptedRefundAmount(100L)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(raceStore.findRefundRequest(100L, "terminal-refund"))
                .get()
                .satisfies(refund -> {
                    assertThat(refund.ledger().status()).isEqualTo(PaymentLedgerStatus.FAILED);
                    assertThat(refund.operationState()).isEqualTo(PaymentOperationState.TERMINAL_FAILED);
                });

        reconcileMismatch();

        assertThat(raceStore.payment().status()).isEqualTo(PaymentStatus.SUSPENDED);
        assertThat(raceStore.savedPaymentCount()).isEqualTo(1);
    }

    @Test
    void customerRefundRequiresReturnShippingBeforeCreatingLedgerOrCallingProvider() {
        when(paymentGateway.refund(any(PaymentOrder.class), any(BigDecimal.class), anyString()))
                .thenReturn(new com.example.monkey.payment.domain.PaymentGatewayResult(
                        PaymentStatus.PARTIALLY_REFUNDED, "refund-trade", null, new BigDecimal("30.00")));

        for (OrderStatus status : List.of(
                OrderStatus.PAID,
                OrderStatus.COMPLETED,
                OrderStatus.RETURN_REQUESTED,
                OrderStatus.WAITING_RETURN_SHIPMENT)) {
            raceStore.resetPayment(PaymentStatus.PAID);
            when(orderStore.findById(10L)).thenReturn(Optional.of(orderWithStatus(status)));
            clearInvocations(paymentStore, paymentGateway, orderStore);

            assertThatThrownBy(() -> service.refund(
                            CUSTOMER,
                            new PaymentRefundRequestDto("PAY100", new BigDecimal("30.00"), "return"),
                            "refund-" + status.name()))
                    .isInstanceOfSatisfying(
                            BusinessException.class,
                            exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.CONFLICT));
            assertThat(raceStore.ledgerEntries()).isEmpty();
            verify(paymentGateway, never()).refund(any(PaymentOrder.class), any(BigDecimal.class), anyString());
            verify(paymentStore, never())
                    .saveLedger(
                            any(PaymentLedgerEntry.class),
                            anyString(),
                            any(PaymentOperationAttempt.class),
                            anyString(),
                            nullable(RefundResponseSnapshot.class),
                            any(RefundAuditIntent.class));
        }
    }

    @Test
    void customerPartialRefundIsAllowedForReturnShippingWithoutClosingOrder() {
        when(orderStore.findById(10L)).thenReturn(Optional.of(orderWithStatus(OrderStatus.RETURN_SHIPPING)));
        when(paymentGateway.refund(any(PaymentOrder.class), any(BigDecimal.class), anyString()))
                .thenReturn(new com.example.monkey.payment.domain.PaymentGatewayResult(
                        PaymentStatus.PARTIALLY_REFUNDED, "refund-trade", null, new BigDecimal("30.00")));
        when(idGenerator.nextId()).thenReturn(3001L);

        var response = service.refund(
                CUSTOMER,
                new PaymentRefundRequestDto("PAY100", new BigDecimal("30.00"), "partial return"),
                "partial-return");

        assertThat(response.refundedAmount()).isEqualByComparingTo(new BigDecimal("30.00"));
        assertThat(raceStore.payment().status()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        verify(orderStore, never())
                .transitionStatus(anyLong(), anyString(), anyString(), nullable(LocalDateTime.class));
    }

    @Test
    void customerFullRefundAtomicallyClosesReturnShippingOrder() {
        when(orderStore.findById(10L)).thenReturn(Optional.of(orderWithStatus(OrderStatus.RETURN_SHIPPING)));
        when(orderStore.transitionStatus(
                        10L, OrderStatus.RETURN_SHIPPING.label(), OrderStatus.REFUNDED.label(), null))
                .thenReturn(1);
        when(paymentGateway.refund(any(PaymentOrder.class), any(BigDecimal.class), anyString()))
                .thenReturn(new com.example.monkey.payment.domain.PaymentGatewayResult(
                        PaymentStatus.REFUNDED, "refund-trade", null, new BigDecimal("100.00")));
        when(idGenerator.nextId()).thenReturn(3002L);

        var response = service.refund(
                CUSTOMER,
                new PaymentRefundRequestDto("PAY100", new BigDecimal("100.00"), "full return"),
                "full-return");

        assertThat(response.refundedAmount()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(raceStore.payment().status()).isEqualTo(PaymentStatus.REFUNDED);
        verify(orderStore)
                .transitionStatus(10L, OrderStatus.RETURN_SHIPPING.label(), OrderStatus.REFUNDED.label(), null);
    }

    @Test
    void fullRefundCasFailureDoesNotCommitPaymentOrClaimSuccess() {
        when(orderStore.findById(10L)).thenReturn(Optional.of(orderWithStatus(OrderStatus.RETURN_SHIPPING)));
        when(orderStore.transitionStatus(
                        10L, OrderStatus.RETURN_SHIPPING.label(), OrderStatus.REFUNDED.label(), null))
                .thenReturn(0);
        when(paymentGateway.refund(any(PaymentOrder.class), any(BigDecimal.class), anyString()))
                .thenReturn(new com.example.monkey.payment.domain.PaymentGatewayResult(
                        PaymentStatus.REFUNDED, "refund-trade", null, new BigDecimal("100.00")));
        when(idGenerator.nextId()).thenReturn(3003L);

        assertThatThrownBy(() -> service.refund(
                        CUSTOMER,
                        new PaymentRefundRequestDto("PAY100", new BigDecimal("100.00"), "full return"),
                        "full-return-cas-failure"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.CONFLICT));

        assertThat(raceStore.payment().status()).isEqualTo(PaymentStatus.PAID);
        assertThat(raceStore.payment().refundedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(raceStore.ledgerEntries()).singleElement().satisfies(ledger -> {
            assertThat(ledger.status()).isEqualTo(PaymentLedgerStatus.ACCEPTED);
            assertThat(raceStore.findRefundRequest(100L, "full-return-cas-failure"))
                    .get()
                    .extracting(PaymentStore.RefundRequest::operationState)
                    .isEqualTo(PaymentOperationState.RETRYABLE);
        });
    }

    @Test
    void adminRefundUsesTheSameReturnShippingGate() {
        when(orderStore.findById(10L)).thenReturn(Optional.of(orderWithStatus(OrderStatus.PAID)));

        assertThatThrownBy(() -> service.refundAsAdmin(
                        new SessionUser(7L, "ADMIN"),
                        new PaymentRefundRequestDto("PAY100", new BigDecimal("30.00"), "admin override"),
                        "admin-refund",
                        "10.0.0.8"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.CONFLICT));

        assertThat(raceStore.ledgerEntries()).isEmpty();
        verify(paymentGateway, never()).refund(any(PaymentOrder.class), any(BigDecimal.class), anyString());
    }

    private PaymentReconciliationResponseDto reconcileMismatch() {
        return service.reconcile(new PaymentReconciliationRequestDto(
                PaymentMethod.WECHAT,
                REPORT_DATE,
                List.of(new ReconciliationLineDto("PAY100", "wrong-trade", new BigDecimal("99.00")))));
    }

    private static void await(CountDownLatch latch, String description) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for " + description);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for " + description, exception);
        }
    }

    private static PaymentOrder paidPayment() {
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
                "payment-key",
                "wx-trade-1",
                null,
                null,
                null,
                LocalDateTime.parse("2026-07-04T08:10:00"),
                LocalDateTime.parse("2026-07-04T08:00:00"),
                LocalDateTime.parse("2026-07-04T08:10:00"));
    }

    private static OrderStore.OrderRecord orderWithStatus(OrderStatus status) {
        return new OrderStore.OrderRecord(
                10L,
                "ORD10",
                42L,
                null,
                null,
                7L,
                "Monkey",
                null,
                new BigDecimal("100.00"),
                null,
                null,
                null,
                null,
                null,
                status.label(),
                LocalDateTime.parse("2026-07-04T08:00:00"),
                false);
    }

    private static final class DirectPaymentTransactions implements PaymentTransactions {
        @Override
        public <T> T execute(java.util.function.Supplier<T> action) {
            return action.get();
        }
    }

    private static final class RaceStore {

        private final Object paymentLock = new Object();
        private final AtomicReference<PaymentOrder> payment = new AtomicReference<>(paidPayment());
        private final List<PaymentLedgerEntry> ledgers = new ArrayList<>();
        private final Map<Long, String> fingerprints = new java.util.HashMap<>();
        private final Map<Long, PaymentOperationAttempt> operations = new java.util.HashMap<>();
        private final Map<Long, String> merchantTokens = new java.util.HashMap<>();
        private final Map<Long, RefundResponseSnapshot> responseSnapshots = new java.util.HashMap<>();
        private final Map<Long, RefundAuditIntent> auditIntents = new java.util.HashMap<>();
        private final AtomicReference<CountDownLatch> candidateReadEntered = new AtomicReference<>();
        private final AtomicReference<CountDownLatch> candidateReadRelease = new AtomicReference<>();
        private int savedPaymentCount;

        private void resetPayment(PaymentStatus status) {
            synchronized (paymentLock) {
                PaymentOrder current = payment.get();
                payment.set(new PaymentOrder(
                        current.id(),
                        current.paymentNo(),
                        current.orderId(),
                        current.userId(),
                        current.method(),
                        current.amount(),
                        current.paidAmount(),
                        BigDecimal.ZERO,
                        status,
                        current.idempotencyKey(),
                        current.providerTradeNo(),
                        current.bankCardNo(),
                        current.bankCardLast4(),
                        current.bankCardBlindIndex(),
                        current.paidAt(),
                        current.createTime(),
                        current.updateTime()));
                ledgers.clear();
                fingerprints.clear();
                operations.clear();
                merchantTokens.clear();
                responseSnapshots.clear();
                auditIntents.clear();
                savedPaymentCount = 0;
            }
        }

        private List<PaymentOrder> paidCandidates() {
            PaymentOrder candidate = payment.get();
            CountDownLatch entered = candidateReadEntered.get();
            CountDownLatch release = candidateReadRelease.get();
            if (entered != null) {
                entered.countDown();
                await(release, "release reconciliation candidate read");
            }
            return candidate == null ? List.of() : List.of(candidate);
        }

        private <T> Optional<T> withLockedPayment(String paymentNo, Function<PaymentOrder, T> operation) {
            synchronized (paymentLock) {
                PaymentOrder current = payment.get();
                if (current == null || !paymentNo.equals(current.paymentNo())) {
                    return Optional.empty();
                }
                return Optional.ofNullable(operation.apply(current));
            }
        }

        private BigDecimal sumAcceptedRefundAmount(Long paymentId) {
            synchronized (paymentLock) {
                return ledgers.stream()
                        .filter(ledger -> paymentId.equals(ledger.paymentId()))
                        .filter(ledger -> PaymentLedgerType.REFUND.equals(ledger.type()))
                        .filter(ledger -> PaymentLedgerStatus.ACCEPTED.equals(ledger.status()))
                        .map(PaymentLedgerEntry::amount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
            }
        }

        private Optional<PaymentStore.RefundRequest> findRefundRequest(Long paymentId, String requestKey) {
            synchronized (paymentLock) {
                return ledgers.stream()
                        .filter(ledger -> paymentId.equals(ledger.paymentId()))
                        .filter(ledger -> PaymentLedgerType.REFUND.equals(ledger.type()))
                        .filter(ledger -> requestKey.equals(ledger.requestKey()))
                        .findFirst()
                        .map(this::toRefundRequest);
            }
        }

        private PaymentOrder savePayment(PaymentOrder updated) {
            synchronized (paymentLock) {
                payment.set(updated);
                savedPaymentCount++;
                return updated;
            }
        }

        private PaymentStore.RefundRequest saveRefundLedger(
                PaymentLedgerEntry ledger,
                String requestFingerprint,
                PaymentOperationAttempt operation,
                String merchantToken,
                RefundResponseSnapshot responseSnapshot,
                RefundAuditIntent auditIntent) {
            synchronized (paymentLock) {
                ledgers.removeIf(existing -> existing.id().equals(ledger.id()));
                ledgers.add(ledger);
                fingerprints.put(ledger.id(), requestFingerprint);
                operations.put(ledger.id(), operation);
                merchantTokens.put(ledger.id(), merchantToken);
                if (responseSnapshot != null) {
                    responseSnapshots.put(ledger.id(), responseSnapshot);
                }
                auditIntents.put(ledger.id(), auditIntent);
                return toRefundRequest(ledger);
            }
        }

        private PaymentStore.RefundRequest toRefundRequest(PaymentLedgerEntry ledger) {
            return new PaymentStore.RefundRequest(
                    ledger,
                    fingerprints.get(ledger.id()),
                    operations.get(ledger.id()),
                    merchantTokens.get(ledger.id()),
                    responseSnapshots.get(ledger.id()),
                    auditIntents.getOrDefault(ledger.id(), RefundAuditIntent.legacy()));
        }

        private void addAcceptedRefund(String requestKey, Long ledgerId, BigDecimal amount) {
            PaymentLedgerEntry ledger = new PaymentLedgerEntry(
                    ledgerId,
                    100L,
                    10L,
                    42L,
                    PaymentLedgerType.REFUND,
                    amount,
                    PaymentLedgerStatus.ACCEPTED,
                    requestKey,
                    null,
                    LocalDateTime.parse("2026-07-04T08:20:00"));
            synchronized (paymentLock) {
                ledgers.add(ledger);
            }
        }

        private void blockCandidateRead(CountDownLatch entered, CountDownLatch release) {
            candidateReadEntered.set(entered);
            candidateReadRelease.set(release);
        }

        private PaymentOrder payment() {
            return payment.get();
        }

        private List<PaymentLedgerEntry> ledgerEntries() {
            synchronized (paymentLock) {
                return List.copyOf(ledgers);
            }
        }

        private int savedPaymentCount() {
            synchronized (paymentLock) {
                return savedPaymentCount;
            }
        }
    }
}
