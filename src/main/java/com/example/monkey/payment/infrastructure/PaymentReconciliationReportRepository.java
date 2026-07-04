package com.example.monkey.payment.infrastructure;

import com.example.monkey.payment.domain.PaymentMethod;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentReconciliationReportRepository extends JpaRepository<PaymentReconciliationReportEntity, Long> {

    Optional<PaymentReconciliationReportEntity> findByProviderAndReportDate(
            PaymentMethod provider, LocalDate reportDate);
}
