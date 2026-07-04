package com.example.monkey.payment.domain;

import java.math.BigDecimal;

public record PaymentGatewayResult(
        PaymentStatus status, String providerTradeNo, String paymentUrl, BigDecimal amount) {}
