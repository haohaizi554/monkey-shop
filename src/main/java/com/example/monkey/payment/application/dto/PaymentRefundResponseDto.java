package com.example.monkey.payment.application.dto;

import com.example.monkey.payment.domain.PaymentLedgerStatus;
import com.example.monkey.payment.domain.PaymentStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PaymentRefundResponseDto(
        Long ledgerId,
        String paymentNo,
        BigDecimal amount,
        BigDecimal refundedAmount,
        PaymentStatus paymentStatus,
        PaymentLedgerStatus ledgerStatus,
        LocalDateTime createTime) {}
