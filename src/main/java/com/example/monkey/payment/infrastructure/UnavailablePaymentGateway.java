package com.example.monkey.payment.infrastructure;

import com.example.monkey.payment.domain.PaymentGateway;
import com.example.monkey.payment.domain.PaymentGatewayResult;
import com.example.monkey.payment.domain.PaymentOrder;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import java.math.BigDecimal;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.payment.gateway", havingValue = "unavailable", matchIfMissing = false)
public final class UnavailablePaymentGateway implements PaymentGateway {

    static final String UNAVAILABLE_MESSAGE =
            "payment gateway is unavailable; configure a real payment adapter before enabling payments";

    @Override
    public PaymentGatewayResult create(PaymentOrder payment, String merchantToken) {
        throw unavailable();
    }

    @Override
    public PaymentGatewayResult query(PaymentOrder payment) {
        throw unavailable();
    }

    @Override
    public PaymentGatewayResult refund(PaymentOrder payment, BigDecimal amount, String merchantToken) {
        throw unavailable();
    }

    private static BusinessException unavailable() {
        return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, UNAVAILABLE_MESSAGE);
    }
}
