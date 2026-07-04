package com.example.monkey.payment.application;

import com.example.monkey.payment.application.dto.PaymentReconciliationResponseDto;
import com.example.monkey.payment.application.dto.PaymentRefundResponseDto;
import com.example.monkey.payment.application.dto.PaymentResponseDto;
import com.example.monkey.payment.domain.PaymentLedgerEntry;
import com.example.monkey.payment.domain.PaymentOrder;
import com.example.monkey.payment.domain.PaymentReconciliationReport;

public final class PaymentDtoAssembler {

    private PaymentDtoAssembler() {}

    public static PaymentResponseDto toResponse(PaymentOrder payment) {
        return toResponse(payment, null);
    }

    public static PaymentResponseDto toResponse(PaymentOrder payment, String paymentUrl) {
        return new PaymentResponseDto(
                payment.id(),
                payment.paymentNo(),
                payment.orderId(),
                payment.userId(),
                payment.method(),
                payment.amount(),
                payment.paidAmount(),
                payment.refundedAmount(),
                payment.status(),
                payment.providerTradeNo(),
                payment.bankCardLast4(),
                paymentUrl,
                payment.paidAt(),
                payment.createTime());
    }

    public static PaymentRefundResponseDto toRefundResponse(PaymentOrder payment, PaymentLedgerEntry ledger) {
        return new PaymentRefundResponseDto(
                ledger.id(),
                payment.paymentNo(),
                ledger.amount(),
                payment.refundedAmount(),
                payment.status(),
                ledger.status(),
                ledger.createTime());
    }

    public static PaymentReconciliationResponseDto toResponse(PaymentReconciliationReport report) {
        return new PaymentReconciliationResponseDto(
                report.id(),
                report.provider(),
                report.reportDate(),
                report.platformAmount(),
                report.providerAmount(),
                report.diffAmount(),
                report.issueCount(),
                report.status(),
                report.createTime());
    }
}
