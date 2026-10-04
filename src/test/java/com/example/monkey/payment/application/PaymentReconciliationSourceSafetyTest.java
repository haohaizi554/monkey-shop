package com.example.monkey.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.order.domain.OrderStore;
import com.example.monkey.payment.domain.PaymentCallbackReplayGuard;
import com.example.monkey.payment.domain.PaymentGateway;
import com.example.monkey.payment.domain.PaymentMethod;
import com.example.monkey.payment.domain.PaymentReconciliationReport;
import com.example.monkey.payment.domain.PaymentReconciliationSource;
import com.example.monkey.payment.domain.PaymentStore;
import com.example.monkey.payment.domain.PaymentTransitionPolicy;
import com.example.monkey.payment.domain.PaymentTransitionResolver;
import com.example.monkey.payment.infrastructure.UnavailablePaymentReconciliationSource;
import com.example.monkey.shared.application.observability.AuditService;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.shared.domain.id.IdGenerator;
import com.example.monkey.shared.domain.inventory.InventoryReservationLifecycle;
import com.example.monkey.user.domain.UserAccountStore;
import com.example.monkey.user.domain.UserMfaVerifier;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PaymentReconciliationSourceSafetyTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-05T08:30:00Z"), ZoneOffset.UTC);
    private static final LocalDate YESTERDAY = LocalDate.of(2026, 7, 4);
    private static final PaymentTransitionResolver TRANSITIONS = (currentStatus, event) ->
            PaymentTransitionPolicy.nextStatus(currentStatus, event).orElseThrow();

    private PaymentStore paymentStore;
    private IdGenerator idGenerator;

    @BeforeEach
    void setUp() {
        paymentStore = mock(PaymentStore.class);
        idGenerator = mock(IdGenerator.class);
        when(paymentStore.findPaidByProviderAndDate(PaymentMethod.WECHAT, YESTERDAY)).thenReturn(List.of());
        when(paymentStore.saveReport(any(PaymentReconciliationReport.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(idGenerator.nextId()).thenReturn(9000L);
    }

    @Test
    void unavailableSourceFailsClosedWithoutReadingOrWritingPaymentState() {
        PaymentApplicationService service = service(new UnavailablePaymentReconciliationSource());

        assertThatThrownBy(service::reconcileYesterday)
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));

        verify(paymentStore, never()).findPaidByProviderAndDate(any(PaymentMethod.class), any(LocalDate.class));
        verify(paymentStore, never()).saveReport(any(PaymentReconciliationReport.class));
        verify(paymentStore, never()).savePayment(any());
    }

    @Test
    void sourceFetchFailureFailsClosedWithoutPersistingAnEmptyReport() {
        PaymentReconciliationSource failingSource = (provider, reportDate) -> {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "provider statement fetch failed");
        };
        PaymentApplicationService service = service(failingSource);

        assertThatThrownBy(service::reconcileYesterday)
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));

        verify(paymentStore, never()).findPaidByProviderAndDate(any(PaymentMethod.class), any(LocalDate.class));
        verify(paymentStore, never()).saveReport(any(PaymentReconciliationReport.class));
        verify(paymentStore, never()).savePayment(any());
    }

    @Test
    void sourceStatementForWrongProviderOrDateFailsClosedBeforeComparison() {
        PaymentReconciliationSource mismatchedSource = (provider, reportDate) ->
                new PaymentReconciliationSource.Statement(PaymentMethod.ALIPAY, reportDate.minusDays(1), List.of());
        PaymentApplicationService service = service(mismatchedSource);

        assertThatThrownBy(service::reconcileYesterday)
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));

        verify(paymentStore, never()).findPaidByProviderAndDate(any(PaymentMethod.class), any(LocalDate.class));
        verify(paymentStore, never()).saveReport(any(PaymentReconciliationReport.class));
        verify(paymentStore, never()).savePayment(any());
    }

    @Test
    void explicitlyAuthoritativeEmptyStatementIsComparedAndCanBeBalanced() {
        PaymentReconciliationSource emptyStatement = (provider, reportDate) ->
                new PaymentReconciliationSource.Statement(provider, reportDate, List.of());
        PaymentApplicationService service = service(emptyStatement);

        var response = service.reconcileYesterday();

        assertThat(response.provider()).isEqualTo(PaymentMethod.WECHAT);
        assertThat(response.reportDate()).isEqualTo(YESTERDAY);
        assertThat(response.status()).isEqualTo(com.example.monkey.payment.domain.ReconciliationStatus.BALANCED);
        assertThat(response.issueCount()).isZero();
        verify(paymentStore).findPaidByProviderAndDate(PaymentMethod.WECHAT, YESTERDAY);
        verify(paymentStore).saveReport(any(PaymentReconciliationReport.class));
    }

    private PaymentApplicationService service(PaymentReconciliationSource source) {
        return new PaymentApplicationService(
                paymentStore,
                mock(PaymentGateway.class),
                mock(PaymentCallbackReplayGuard.class),
                TRANSITIONS,
                mock(OrderStore.class),
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
                "callback-secret",
                source);
    }

    private static final class DirectPaymentTransactions implements PaymentTransactions {
        @Override
        public <T> T execute(Supplier<T> action) {
            return action.get();
        }
    }
}
