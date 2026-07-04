package com.example.monkey.payment.application.dto;

import com.example.monkey.payment.domain.PaymentMethod;
import com.example.monkey.payment.domain.PaymentStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PaymentResponseDto(
        Long id,
        String paymentNo,
        Long orderId,
        Long userId,
        PaymentMethod method,
        BigDecimal amount,
        BigDecimal paidAmount,
        BigDecimal refundedAmount,
        PaymentStatus status,
        String providerTradeNo,
        String bankCardLast4,
        String paymentUrl,
        LocalDateTime paidAt,
        LocalDateTime createTime) {}
