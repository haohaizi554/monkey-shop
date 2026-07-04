package com.example.monkey.payment.domain;

public interface PaymentTransitionResolver {

    PaymentStatus nextStatus(PaymentStatus currentStatus, PaymentEvent event);
}
