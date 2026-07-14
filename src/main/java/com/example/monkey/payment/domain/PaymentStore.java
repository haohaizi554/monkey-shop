package com.example.monkey.payment.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

public interface PaymentStore {

    record PaymentIntent(PaymentOrder payment, String requestFingerprint, String paymentUrl) {}

    record RefundRequest(PaymentLedgerEntry ledger, String requestFingerprint) {}

    Optional<PaymentOrder> findByPaymentNo(String paymentNo);

    Optional<PaymentOrder> findByOrderIdAndUserId(Long orderId, Long userId);

    Optional<PaymentIntent> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

    <T> Optional<T> withLockedPayment(String paymentNo, Function<PaymentOrder, T> operation);

    Optional<PaymentLedgerEntry> findLedger(Long paymentId, PaymentLedgerType type, String requestKey);

    Optional<RefundRequest> findRefundRequest(Long paymentId, String requestKey);

    PaymentOrder savePayment(PaymentOrder payment);

    PaymentIntent savePayment(PaymentOrder payment, String requestFingerprint, String paymentUrl);

    PaymentLedgerEntry saveLedger(PaymentLedgerEntry ledger);

    RefundRequest saveLedger(PaymentLedgerEntry ledger, String requestFingerprint);

    List<PaymentOrder> findPendingCreatedBefore(LocalDateTime cutoff, int limit);

    List<PaymentOrder> findPaidByProviderAndDate(PaymentMethod provider, LocalDate reportDate);

    PaymentReconciliationReport saveReport(PaymentReconciliationReport report);
}
