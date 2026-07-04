package com.example.monkey.payment.domain;

import java.time.Duration;

public interface PaymentCallbackReplayGuard {

    boolean reserve(PaymentMethod provider, String paymentNo, String callbackId, Duration ttl);
}
