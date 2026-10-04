package com.example.monkey.payment.infrastructure;

import com.example.monkey.payment.domain.PaymentMethod;
import com.example.monkey.payment.domain.PaymentReconciliationSource;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import java.time.LocalDate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Production-safe default until a provider adapter can return a complete statement.
 *
 * <p>Failing here prevents the scheduled job from treating an absent statement as an authoritative empty report.
 */
@Component
@ConditionalOnMissingBean(PaymentReconciliationSource.class)
public final class UnavailablePaymentReconciliationSource implements PaymentReconciliationSource {

    static final String UNAVAILABLE_MESSAGE =
            "payment reconciliation provider statement is unavailable; configure a complete provider adapter before enabling reconciliation";

    @Override
    public Statement fetch(PaymentMethod provider, LocalDate reportDate) {
        throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, UNAVAILABLE_MESSAGE);
    }
}
