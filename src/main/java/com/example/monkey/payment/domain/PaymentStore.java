package com.example.monkey.payment.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

public interface PaymentStore {

    Optional<PaymentOrder> findByPaymentNo(String paymentNo);

    Optional<PaymentOrder> findByOrderIdAndUserId(Long orderId, Long userId);

    Optional<PaymentOrder> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

    <T> Optional<T> withLockedPayment(String paymentNo, Function<PaymentOrder, T> operation);

    Optional<PaymentLedgerEntry> findLedger(Long paymentId, PaymentLedgerType type, String requestKey);

    PaymentOrder savePayment(PaymentOrder payment);

    PaymentLedgerEntry saveLedger(PaymentLedgerEntry ledger);

    List<PaymentOrder> findPendingCreatedBefore(LocalDateTime cutoff, int limit);

    List<PaymentOrder> findPaidByProviderAndDate(PaymentMethod provider, LocalDate reportDate);

    PaymentReconciliationReport saveReport(PaymentReconciliationReport report);
}
