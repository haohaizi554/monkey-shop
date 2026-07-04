package com.example.monkey.payment.infrastructure;

import com.example.monkey.payment.domain.PaymentLedgerType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentLedgerRepository extends JpaRepository<PaymentLedgerEntity, Long> {

    Optional<PaymentLedgerEntity> findByPaymentIdAndLedgerTypeAndRequestKey(
            Long paymentId, PaymentLedgerType ledgerType, String requestKey);
}
