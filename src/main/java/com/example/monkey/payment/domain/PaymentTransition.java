package com.example.monkey.payment.domain;

public record PaymentTransition(PaymentStatus source, PaymentEvent event, PaymentStatus target) {}
