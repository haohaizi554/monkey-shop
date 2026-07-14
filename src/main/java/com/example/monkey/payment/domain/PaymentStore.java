package com.example.monkey.payment.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

public interface PaymentStore {

    record PaymentIntent(
            PaymentOrder payment,
            String requestFingerprint,
            PaymentOperationAttempt operation,
            String merchantToken,
            PaymentResponseSnapshot responseSnapshot) {

        public PaymentOperationState operationState() {
            return operation.state();
        }
    }

    record RefundRequest(
            PaymentLedgerEntry ledger,
            String requestFingerprint,
            PaymentOperationAttempt operation,
            String merchantToken,
            RefundResponseSnapshot responseSnapshot,
            RefundAuditIntent auditIntent) {

        public PaymentOperationState operationState() {
            return operation.state();
        }
    }

    Optional<PaymentOrder> findByPaymentNo(String paymentNo);

    Optional<PaymentOrder> findById(Long paymentId);

    Optional<PaymentOrder> findByOrderIdAndUserId(Long orderId, Long userId);

    Optional<PaymentIntent> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

    Optional<PaymentIntent> findActiveByOrderId(Long orderId);

    <T> Optional<T> withLockedPayment(String paymentNo, Function<PaymentOrder, T> operation);

    Optional<PaymentLedgerEntry> findLedger(Long paymentId, PaymentLedgerType type, String requestKey);

    Optional<RefundRequest> findRefundRequest(Long paymentId, String requestKey);

    BigDecimal sumAcceptedRefundAmount(Long paymentId);

    PaymentOrder savePayment(PaymentOrder payment);

    PaymentIntent savePayment(
            PaymentOrder payment,
            String requestFingerprint,
            PaymentOperationAttempt operation,
            String merchantToken,
            PaymentResponseSnapshot responseSnapshot);

    PaymentLedgerEntry saveLedger(PaymentLedgerEntry ledger);

    RefundRequest saveLedger(
            PaymentLedgerEntry ledger,
            String requestFingerprint,
            PaymentOperationAttempt operation,
            String merchantToken,
            RefundResponseSnapshot responseSnapshot,
            RefundAuditIntent auditIntent);

    List<PaymentIntent> findExpiredPaymentOperations(LocalDateTime cutoff, int limit);

    List<RefundRequest> findExpiredRefundOperations(LocalDateTime cutoff, int limit);

    List<RefundRequest> findPendingRefundAudits(int limit);

    List<PaymentOrder> findPendingCreatedBefore(LocalDateTime cutoff, int limit);

    List<PaymentOrder> findPaidByProviderAndDate(PaymentMethod provider, LocalDate reportDate);

    PaymentReconciliationReport saveReport(PaymentReconciliationReport report);
}
