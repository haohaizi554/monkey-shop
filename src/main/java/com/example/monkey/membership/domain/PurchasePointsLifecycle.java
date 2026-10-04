package com.example.monkey.membership.domain;

import java.math.BigDecimal;

/**
 * Narrow payment-to-membership boundary. Implementations must resolve and persist all reward state in the current
 * tenant transaction; callers must supply only facts read from the authoritative payment aggregate.
 */
public interface PurchasePointsLifecycle {

    void onPaymentSucceeded(PurchasePayment payment);

    void onRefundSucceeded(PurchaseRefund refund);

    record PurchasePayment(
            Long tenantId,
            Long paymentId,
            Long orderId,
            Long userId,
            BigDecimal paidAmount,
            String providerTradeNo,
            String eventKey) {}

    record PurchaseRefund(
            Long tenantId,
            Long paymentId,
            Long orderId,
            Long userId,
            BigDecimal originalPaidAmount,
            BigDecimal refundAmount,
            BigDecimal cumulativeRefundedAmount,
            String providerTradeNo,
            String eventKey) {}
}
