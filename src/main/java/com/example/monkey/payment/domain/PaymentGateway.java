package com.example.monkey.payment.domain;

import java.math.BigDecimal;

public interface PaymentGateway {

    PaymentGatewayResult create(PaymentOrder payment, String merchantToken);

    PaymentGatewayResult query(PaymentOrder payment);

    PaymentGatewayResult refund(PaymentOrder payment, BigDecimal amount, String merchantToken);
}
