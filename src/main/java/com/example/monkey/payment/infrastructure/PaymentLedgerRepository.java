package com.example.monkey.payment.infrastructure;

import com.example.monkey.payment.domain.PaymentLedgerStatus;
import com.example.monkey.payment.domain.PaymentLedgerType;
import com.example.monkey.payment.domain.PaymentOperationState;
import com.example.monkey.payment.domain.RefundAuditState;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentLedgerRepository extends JpaRepository<PaymentLedgerEntity, Long> {

    Optional<PaymentLedgerEntity> findByPaymentIdAndLedgerTypeAndRequestKey(
            Long paymentId, PaymentLedgerType ledgerType, String requestKey);

    @Query("""
            select coalesce(sum(ledger.amount), 0)
            from PaymentLedgerEntity ledger
            where ledger.paymentId = :paymentId
              and ledger.ledgerType = :ledgerType
              and ledger.status = :status
            """)
    BigDecimal sumAmountByPaymentIdAndTypeAndStatus(
            @Param("paymentId") Long paymentId,
            @Param("ledgerType") PaymentLedgerType ledgerType,
            @Param("status") PaymentLedgerStatus status);

    List<PaymentLedgerEntity> findByLedgerTypeAndOperationStateInAndLeaseExpiresAtLessThanEqualOrderByLeaseExpiresAtAsc(
            PaymentLedgerType ledgerType,
            Collection<PaymentOperationState> states,
            LocalDateTime cutoff,
            Pageable pageable);

    List<PaymentLedgerEntity> findByLedgerTypeAndAuditStateOrderByCreateTimeAsc(
            PaymentLedgerType ledgerType, RefundAuditState auditState, Pageable pageable);
}
