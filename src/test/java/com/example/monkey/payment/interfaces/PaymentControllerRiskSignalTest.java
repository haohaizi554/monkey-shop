package com.example.monkey.payment.interfaces;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.payment.application.PaymentApplicationService;
import com.example.monkey.payment.application.dto.PaymentCreateRequestDto;
import com.example.monkey.payment.application.dto.PaymentResponseDto;
import com.example.monkey.payment.domain.PaymentMethod;
import com.example.monkey.payment.domain.PaymentStatus;
import com.example.monkey.shared.application.security.SessionUser;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class PaymentControllerRiskSignalTest {

    @Test
    void forwardsDeviceAndResolvedClientIpToApplicationBoundary() {
        PaymentApplicationService paymentApplicationService = mock(PaymentApplicationService.class);
        PaymentController controller = new PaymentController(paymentApplicationService);
        HttpServletRequest request = mock(HttpServletRequest.class);
        SessionUser user = new SessionUser(42L, "USER");
        PaymentCreateRequestDto createRequest = new PaymentCreateRequestDto(10L, PaymentMethod.WECHAT, null, "123456");
        PaymentResponseDto response = new PaymentResponseDto(
                100L,
                "PAY100",
                10L,
                42L,
                PaymentMethod.WECHAT,
                new BigDecimal("100.00"),
                new BigDecimal("100.00"),
                BigDecimal.ZERO,
                PaymentStatus.PAID,
                "wx-trade-1",
                null,
                null,
                LocalDateTime.parse("2026-08-28T08:00:00"),
                LocalDateTime.parse("2026-08-28T07:00:00"));
        when(request.getAttribute(org.mockito.ArgumentMatchers.anyString())).thenReturn(null);
        when(request.getRemoteAddr()).thenReturn("203.0.113.9");
        when(paymentApplicationService.createPayment(user, createRequest, "payment-key", "device-a", "203.0.113.9"))
                .thenReturn(response);

        var result = controller.createPayment("payment-key", "device-a", createRequest, user, request);

        assertThat(result.data()).isEqualTo(response);
        verify(paymentApplicationService)
                .createPayment(eq(user), eq(createRequest), eq("payment-key"), eq("device-a"), eq("203.0.113.9"));
    }
}
