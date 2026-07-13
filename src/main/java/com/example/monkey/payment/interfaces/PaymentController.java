package com.example.monkey.payment.interfaces;

import com.example.monkey.payment.application.PaymentApplicationService;
import com.example.monkey.payment.application.dto.PaymentCallbackRequestDto;
import com.example.monkey.payment.application.dto.PaymentCreateRequestDto;
import com.example.monkey.payment.application.dto.PaymentReconciliationRequestDto;
import com.example.monkey.payment.application.dto.PaymentReconciliationResponseDto;
import com.example.monkey.payment.application.dto.PaymentRefundRequestDto;
import com.example.monkey.payment.application.dto.PaymentRefundResponseDto;
import com.example.monkey.payment.application.dto.PaymentResponseDto;
import com.example.monkey.risk.application.RiskApplicationService;
import com.example.monkey.risk.application.dto.RiskAssessmentRequestDto;
import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.interfaces.dto.Result;
import com.example.monkey.shared.interfaces.web.ClientIps;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
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
@RequestMapping({"/api/payments", "/api/v1/payments"})
public class PaymentController {

    private final PaymentApplicationService paymentApplicationService;
    private final RiskApplicationService riskApplicationService;

    public PaymentController(
            PaymentApplicationService paymentApplicationService, RiskApplicationService riskApplicationService) {
        this.paymentApplicationService = paymentApplicationService;
        this.riskApplicationService = riskApplicationService;
    }

    @PostMapping("/pay")
    @PreAuthorize("hasAuthority('ORDER_CREATE')")
    public Result<PaymentResponseDto> createPayment(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Device-Fingerprint", required = false) String deviceFingerprint,
            @Valid @RequestBody PaymentCreateRequestDto request,
            @AuthenticationPrincipal SessionUser currentUser,
            HttpServletRequest httpRequest) {
        riskApplicationService.requireAllowed(
                currentUser,
                new RiskAssessmentRequestDto(
                        null,
                        deviceFingerprint,
                        null,
                        null,
                        request.orderId(),
                        null,
                        null,
                        null,
                        null,
                        request.totpCode()),
                ClientIps.resolve(httpRequest),
                "payment.create");
        return Result.success(paymentApplicationService.createPayment(currentUser, request, idempotencyKey));
    }

    @PostMapping("/callback")
    @PreAuthorize("permitAll()")
    public Result<PaymentResponseDto> callback(
            @Valid @RequestBody PaymentCallbackRequestDto request, HttpServletRequest httpRequest) {
        return Result.success(paymentApplicationService.handleCallback(request, ClientIps.resolve(httpRequest)));
    }

    @PostMapping("/refund")
    @PreAuthorize("hasAuthority('ORDER_READ_OWN')")
    public Result<PaymentRefundResponseDto> refund(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PaymentRefundRequestDto request,
            @AuthenticationPrincipal SessionUser currentUser) {
        return Result.success(paymentApplicationService.refund(currentUser, request, idempotencyKey));
    }

    @GetMapping("/orders/{orderId}")
    @PreAuthorize("hasAuthority('ORDER_READ_OWN')")
    public Result<PaymentResponseDto> findByOrder(
            @PathVariable Long orderId, @AuthenticationPrincipal SessionUser currentUser) {
        return Result.success(paymentApplicationService.findByOrder(currentUser, orderId));
    }

    @PostMapping("/reconciliation")
    @PreAuthorize("hasAuthority('ORDER_MANAGE')")
    public Result<PaymentReconciliationResponseDto> reconcile(
            @Valid @RequestBody PaymentReconciliationRequestDto request) {
        return Result.success(paymentApplicationService.reconcile(request));
    }
}
