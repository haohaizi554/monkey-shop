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
            PaymentResponseSnapshot responseSnapshot,
            PaymentQueryAttempt queryAttempt) {

        public PaymentIntent(
                PaymentOrder payment,
                String requestFingerprint,
                PaymentOperationAttempt operation,
                String merchantToken,
                PaymentResponseSnapshot responseSnapshot) {
            this(
                    payment,
                    requestFingerprint,
                    operation,
                    merchantToken,
                    responseSnapshot,
                    PaymentQueryAttempt.notScheduled());
        }

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

    /**
     * Returns the tenant that owns a payment number, even when the current tenant filter hides that row.
     *
     * <p>The default keeps lightweight in-memory stores source-compatible; the JPA store overrides it with a
     * tenant-independent lookup so signed provider callbacks cannot be replayed in another tenant context.
     */
    default Optional<Long> findTenantIdByPaymentNo(String paymentNo) {
        return Optional.empty();
    }

    Optional<PaymentOrder> findById(Long paymentId);

    Optional<PaymentOrder> findByOrderIdAndUserId(Long orderId, Long userId);

    Optional<PaymentIntent> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

    Optional<PaymentIntent> findPaymentIntentByPaymentNo(String paymentNo);

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

    PaymentIntent savePaymentQueryAttempt(PaymentOrder payment, PaymentQueryAttempt queryAttempt);

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

    List<PaymentIntent> findPaymentsReadyForQuery(LocalDateTime readyAt, int limit);

    List<PaymentOrder> findPaidByProviderAndDate(PaymentMethod provider, LocalDate reportDate);

    PaymentReconciliationReport saveReport(PaymentReconciliationReport report);
}
