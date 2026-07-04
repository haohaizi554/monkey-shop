package com.example.monkey.payment.application.dto;

import com.example.monkey.payment.domain.PaymentMethod;
import com.example.monkey.payment.domain.ReconciliationStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record PaymentReconciliationResponseDto(
        Long id,
        PaymentMethod provider,
        LocalDate reportDate,
        BigDecimal platformAmount,
        BigDecimal providerAmount,
        BigDecimal diffAmount,
        int issueCount,
        ReconciliationStatus status,
        LocalDateTime createTime) {}
