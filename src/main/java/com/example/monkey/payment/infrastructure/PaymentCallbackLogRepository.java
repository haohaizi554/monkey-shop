package com.example.monkey.payment.infrastructure;

import com.example.monkey.payment.domain.PaymentMethod;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentCallbackLogRepository extends JpaRepository<PaymentCallbackLogEntity, Long> {

    Optional<PaymentCallbackLogEntity> findByProviderAndCallbackId(PaymentMethod provider, String callbackId);
}
