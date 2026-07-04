package com.example.monkey.payment.infrastructure;

import com.example.monkey.payment.domain.PaymentGateway;
import com.example.monkey.payment.domain.PaymentGatewayResult;
import com.example.monkey.payment.domain.PaymentOrder;
import com.example.monkey.payment.domain.PaymentStatus;
import java.math.BigDecimal;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.payment.gateway", havingValue = "sandbox", matchIfMissing = true)
public class SandboxPaymentGateway implements PaymentGateway {

    @Override
    public PaymentGatewayResult create(PaymentOrder payment) {
        String tradeNo = "SANDBOX-" + payment.method() + "-" + payment.paymentNo();
        return new PaymentGatewayResult(
                PaymentStatus.PENDING, tradeNo, "/sandbox/payments/" + payment.paymentNo(), payment.amount());
    }

    @Override
    public PaymentGatewayResult query(PaymentOrder payment) {
        return new PaymentGatewayResult(payment.status(), payment.providerTradeNo(), null, payment.amount());
    }

    @Override
    public PaymentGatewayResult refund(PaymentOrder payment, BigDecimal amount, String requestKey) {
        return new PaymentGatewayResult(
                PaymentStatus.REFUNDED, "RF-" + payment.paymentNo() + "-" + requestKey, null, amount);
    }
}
