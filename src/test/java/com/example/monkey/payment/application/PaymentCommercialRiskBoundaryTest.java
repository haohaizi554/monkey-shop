package com.example.monkey.payment.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.monkey.order.domain.OrderStore;
import com.example.monkey.payment.application.dto.PaymentCreateRequestDto;
import com.example.monkey.payment.domain.PaymentCallbackReplayGuard;
import com.example.monkey.payment.domain.PaymentGateway;
import com.example.monkey.payment.domain.PaymentMethod;
import com.example.monkey.payment.domain.PaymentStore;
import com.example.monkey.payment.domain.PaymentTransitionPolicy;
import com.example.monkey.payment.domain.PaymentTransitionResolver;
import com.example.monkey.risk.domain.CommercialRiskGate;
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
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PaymentCommercialRiskBoundaryTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-28T08:00:00Z"), ZoneOffset.UTC);
    private static final SessionUser CUSTOMER = new SessionUser(42L, "USER");

    @Test
    void directPaymentCallIsRiskCheckedBeforeAnyReservationOrGatewaySideEffect() {
        PaymentStore paymentStore = mock(PaymentStore.class);
        PaymentGateway paymentGateway = mock(PaymentGateway.class);
        OrderStore orderStore = mock(OrderStore.class);
        CommercialRiskGate riskGate = mock(CommercialRiskGate.class);
        OrderStore.OrderRecord authoritativeOrder = order(10L, 42L, new BigDecimal("100.00"));
        when(orderStore.findVisibleByIdAndUserId(10L, 42L)).thenReturn(Optional.of(authoritativeOrder));
        BusinessException blocked = new BusinessException(ErrorCode.FORBIDDEN, "risk blocked");
        doThrow(blocked)
                .when(riskGate)
                .requireAllowed(
                        eq(42L),
                        isNull(),
                        isNull(),
                        eq(10L),
                        eq("device-a"),
                        eq("203.0.113.7"),
                        eq("payment.create"),
                        eq("123456"));

        PaymentApplicationService service = service(paymentStore, paymentGateway, orderStore, riskGate);

        assertThatThrownBy(() -> service.createPayment(
                        CUSTOMER,
                        new PaymentCreateRequestDto(10L, PaymentMethod.WECHAT, null, "123456"),
                        "payment-key",
                        "device-a",
                        "203.0.113.7"))
                .isSameAs(blocked);

        verify(orderStore).findVisibleByIdAndUserId(10L, 42L);
        verify(riskGate).requireAllowed(42L, null, null, 10L, "device-a", "203.0.113.7", "payment.create", "123456");
        verify(paymentStore, never()).findByUserIdAndIdempotencyKey(anyLong(), any());
        verify(paymentStore, never()).savePayment(any(), any(), any(), any(), any());
        verifyNoInteractions(paymentGateway);
    }

    private static PaymentApplicationService service(
            PaymentStore paymentStore,
            PaymentGateway paymentGateway,
            OrderStore orderStore,
            CommercialRiskGate riskGate) {
        PaymentTransitionResolver transitions = (status, event) ->
                PaymentTransitionPolicy.nextStatus(status, event).orElseThrow();
        return new PaymentApplicationService(
                paymentStore,
                paymentGateway,
                mock(PaymentCallbackReplayGuard.class),
                transitions,
                orderStore,
                mock(InventoryReservationLifecycle.class),
                mock(UserAccountStore.class),
                mock(UserMfaVerifier.class),
                mock(IdGenerator.class),
                mock(AuditService.class),
                new PaymentTransactions() {
                    @Override
                    public <T> T execute(java.util.function.Supplier<T> action) {
                        return action.get();
                    }
                },
                CLOCK,
                Duration.ofHours(24),
                Duration.ofMinutes(5),
                new BigDecimal("5000.00"),
                "callback-secret",
                riskGate);
    }

    private static OrderStore.OrderRecord order(Long orderId, Long userId, BigDecimal amount) {
        return new OrderStore.OrderRecord(
                orderId,
                "ORD" + orderId,
                userId,
                "buyer",
                null,
                7L,
                "Monkey",
                null,
                amount,
                null,
                null,
                null,
                null,
                null,
                "PAID",
                LocalDateTime.parse("2026-08-28T07:00:00"),
                false);
    }
}
