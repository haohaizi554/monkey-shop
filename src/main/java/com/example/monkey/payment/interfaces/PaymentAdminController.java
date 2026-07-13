package com.example.monkey.payment.interfaces;

import com.example.monkey.payment.application.PaymentApplicationService;
import com.example.monkey.payment.application.dto.PaymentRefundRequestDto;
import com.example.monkey.payment.application.dto.PaymentRefundResponseDto;
import com.example.monkey.payment.application.dto.PaymentResponseDto;
import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.interfaces.dto.Result;
import com.example.monkey.shared.interfaces.web.ClientIps;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/payments/admin", "/api/v1/payments/admin"})
public class PaymentAdminController {

    private final PaymentApplicationService paymentApplicationService;

    public PaymentAdminController(PaymentApplicationService paymentApplicationService) {
        this.paymentApplicationService = paymentApplicationService;
    }

    @GetMapping("/orders/{orderId}")
    @PreAuthorize("hasAuthority('ORDER_MANAGE')")
    public Result<PaymentResponseDto> findByOrder(
            @PathVariable Long orderId,
            @AuthenticationPrincipal SessionUser currentUser,
            HttpServletRequest httpRequest) {
        return Result.success(
                paymentApplicationService.findByOrderAsAdmin(currentUser, orderId, ClientIps.resolve(httpRequest)));
    }

    @PostMapping("/refund")
    @PreAuthorize("hasAuthority('ORDER_MANAGE')")
    public Result<PaymentRefundResponseDto> refund(
            @RequestHeader(value = "Idempotency-Key") @NotBlank String idempotencyKey,
            @Valid @RequestBody PaymentRefundRequestDto request,
            @AuthenticationPrincipal SessionUser currentUser,
            HttpServletRequest httpRequest) {
        return Result.success(paymentApplicationService.refundAsAdmin(
                currentUser, request, idempotencyKey, ClientIps.resolve(httpRequest)));
    }
}
