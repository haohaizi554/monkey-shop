package com.example.monkey.payment.infrastructure;

import com.example.monkey.payment.domain.PaymentFailureClassification;
import com.example.monkey.payment.domain.PaymentLedgerEntry;
import com.example.monkey.payment.domain.PaymentLedgerType;
import com.example.monkey.payment.domain.PaymentMethod;
import com.example.monkey.payment.domain.PaymentOperationAttempt;
import com.example.monkey.payment.domain.PaymentOperationState;
import com.example.monkey.payment.domain.PaymentOrder;
import com.example.monkey.payment.domain.PaymentReconciliationReport;
import com.example.monkey.payment.domain.PaymentResponseSnapshot;
import com.example.monkey.payment.domain.PaymentStatus;
import com.example.monkey.payment.domain.PaymentStore;
import com.example.monkey.payment.domain.RefundAuditIntent;
import com.example.monkey.payment.domain.RefundAuditState;
import com.example.monkey.payment.domain.RefundResponseSnapshot;
import com.example.monkey.shared.infrastructure.privacy.PiiCryptoService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Component
@ConditionalOnProperty(name = "app.payment.store", havingValue = "jpa", matchIfMissing = true)
public class JpaPaymentStore implements PaymentStore {

    private final PaymentOrderRepository paymentOrderRepository;
    private final PaymentLedgerRepository paymentLedgerRepository;
    private final PaymentReconciliationReportRepository reconciliationReportRepository;
    private final PiiCryptoService piiCryptoService;

    public JpaPaymentStore(
            PaymentOrderRepository paymentOrderRepository,
            PaymentLedgerRepository paymentLedgerRepository,
            PaymentReconciliationReportRepository reconciliationReportRepository,
            PiiCryptoService piiCryptoService) {
        this.paymentOrderRepository = paymentOrderRepository;
        this.paymentLedgerRepository = paymentLedgerRepository;
        this.reconciliationReportRepository = reconciliationReportRepository;
        this.piiCryptoService = piiCryptoService;
    }

    @Override
    public Optional<PaymentOrder> findByPaymentNo(String paymentNo) {
        return paymentOrderRepository.findByPaymentNo(paymentNo).map(this::toDomain);
    }

    @Override
    public Optional<PaymentOrder> findById(Long paymentId) {
        return paymentOrderRepository.findById(paymentId).map(this::toDomain);
    }

    @Override
    public Optional<PaymentOrder> findByOrderIdAndUserId(Long orderId, Long userId) {
        return paymentOrderRepository
                .findFirstByOrderIdAndUserIdOrderByCreateTimeDesc(orderId, userId)
                .map(this::toDomain);
    }

    @Override
    public Optional<PaymentIntent> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey) {
        return paymentOrderRepository
                .findByUserIdAndIdempotencyKey(userId, idempotencyKey)
                .map(this::toPaymentIntent);
    }

    @Override
    public Optional<PaymentIntent> findActiveByOrderId(Long orderId) {
        return paymentOrderRepository
                .findFirstByOrderIdAndStatusInOrderByCreateTimeDesc(
                        orderId,
                        List.of(
                                PaymentStatus.PENDING,
                                PaymentStatus.PAID,
                                PaymentStatus.PARTIALLY_REFUNDED,
                                PaymentStatus.SUSPENDED))
                .map(this::toPaymentIntent);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public <T> Optional<T> withLockedPayment(String paymentNo, Function<PaymentOrder, T> operation) {
        return paymentOrderRepository
                .findByPaymentNoForUpdate(paymentNo)
                .map(this::toDomain)
                .map(operation);
    }

    @Override
    public Optional<PaymentLedgerEntry> findLedger(Long paymentId, PaymentLedgerType type, String requestKey) {
        return paymentLedgerRepository
                .findByPaymentIdAndLedgerTypeAndRequestKey(paymentId, type, requestKey)
                .map(JpaPaymentStore::toDomain);
    }

    @Override
    public Optional<RefundRequest> findRefundRequest(Long paymentId, String requestKey) {
        return paymentLedgerRepository
                .findByPaymentIdAndLedgerTypeAndRequestKey(paymentId, PaymentLedgerType.REFUND, requestKey)
                .map(JpaPaymentStore::toRefundRequest);
    }

    @Override
    public BigDecimal sumAcceptedRefundAmount(Long paymentId) {
        BigDecimal amount = paymentLedgerRepository.sumAmountByPaymentIdAndTypeAndStatus(
                paymentId, PaymentLedgerType.REFUND, com.example.monkey.payment.domain.PaymentLedgerStatus.ACCEPTED);
        return amount == null ? BigDecimal.ZERO : amount;
    }

    @Override
    public PaymentOrder savePayment(PaymentOrder payment) {
        PaymentOrderEntity existing = payment.id() == null
                ? null
                : paymentOrderRepository.findById(payment.id()).orElse(null);
        PaymentOrderEntity saved = paymentOrderRepository.save(toEntity(payment, existing));
        return toDomain(saved);
    }

    @Override
    public PaymentIntent savePayment(
            PaymentOrder payment,
            String requestFingerprint,
            PaymentOperationAttempt operation,
            String merchantToken,
            PaymentResponseSnapshot responseSnapshot) {
        PaymentOrderEntity existing = payment.id() == null
                ? null
                : paymentOrderRepository.findById(payment.id()).orElse(null);
        PaymentOrderEntity entity = toEntity(payment, existing);
        entity.setRequestFingerprint(requestFingerprint);
        applyOperation(entity, operation);
        entity.setMerchantToken(merchantToken);
        applyResponseSnapshot(entity, responseSnapshot);
        PaymentOrderEntity saved =
                existing == null ? paymentOrderRepository.saveAndFlush(entity) : paymentOrderRepository.save(entity);
        return toPaymentIntent(saved);
    }

    @Override
    public PaymentLedgerEntry saveLedger(PaymentLedgerEntry ledger) {
        return toDomain(paymentLedgerRepository.save(toEntity(ledger)));
    }

    @Override
    public RefundRequest saveLedger(
            PaymentLedgerEntry ledger,
            String requestFingerprint,
            PaymentOperationAttempt operation,
            String merchantToken,
            RefundResponseSnapshot responseSnapshot,
            RefundAuditIntent auditIntent) {
        PaymentLedgerEntity entity = paymentLedgerRepository
                .findById(ledger.id())
                .map(existing -> toEntity(ledger, existing))
                .orElseGet(() -> toEntity(ledger));
        entity.setRequestFingerprint(requestFingerprint);
        applyOperation(entity, operation);
        entity.setMerchantToken(merchantToken);
        applyResponseSnapshot(entity, responseSnapshot);
        applyAuditIntent(entity, auditIntent);
        PaymentLedgerEntity saved = PaymentOperationState.RESERVED.equals(operation.state())
                ? paymentLedgerRepository.saveAndFlush(entity)
                : paymentLedgerRepository.save(entity);
        return toRefundRequest(saved);
    }

    @Override
    public List<PaymentIntent> findExpiredPaymentOperations(LocalDateTime cutoff, int limit) {
        return paymentOrderRepository
                .findByOperationStateInAndLeaseExpiresAtLessThanEqualOrderByLeaseExpiresAtAsc(
                        List.of(PaymentOperationState.RESERVED, PaymentOperationState.RETRYABLE),
                        cutoff,
                        PageRequest.of(0, Math.max(1, limit)))
                .stream()
                .map(this::toPaymentIntent)
                .toList();
    }

    @Override
    public List<RefundRequest> findExpiredRefundOperations(LocalDateTime cutoff, int limit) {
        return paymentLedgerRepository
                .findByLedgerTypeAndOperationStateInAndLeaseExpiresAtLessThanEqualOrderByLeaseExpiresAtAsc(
                        PaymentLedgerType.REFUND,
                        List.of(PaymentOperationState.RESERVED, PaymentOperationState.RETRYABLE),
                        cutoff,
                        PageRequest.of(0, Math.max(1, limit)))
                .stream()
                .map(JpaPaymentStore::toRefundRequest)
                .toList();
    }

    @Override
    public List<RefundRequest> findPendingRefundAudits(int limit) {
        return paymentLedgerRepository
                .findByLedgerTypeAndAuditStateOrderByCreateTimeAsc(
                        PaymentLedgerType.REFUND, RefundAuditState.PENDING, PageRequest.of(0, Math.max(1, limit)))
                .stream()
                .map(JpaPaymentStore::toRefundRequest)
                .toList();
    }

    @Override
    public List<PaymentOrder> findPendingCreatedBefore(LocalDateTime cutoff, int limit) {
        return paymentOrderRepository
                .findByStatusAndOperationStateAndCreateTimeBeforeOrderByCreateTimeAsc(
                        PaymentStatus.PENDING,
                        PaymentOperationState.COMPLETED,
                        cutoff,
                        PageRequest.of(0, Math.max(1, limit)))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<PaymentOrder> findPaidByProviderAndDate(PaymentMethod provider, LocalDate reportDate) {
        LocalDateTime start = reportDate.atStartOfDay();
        LocalDateTime end = reportDate.plusDays(1).atStartOfDay();
        return paymentOrderRepository
                .findByMethodAndPaidAtGreaterThanEqualAndPaidAtLessThanAndStatusIn(
                        provider,
                        start,
                        end,
                        List.of(PaymentStatus.PAID, PaymentStatus.PARTIALLY_REFUNDED, PaymentStatus.REFUNDED))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public PaymentReconciliationReport saveReport(PaymentReconciliationReport report) {
        return toDomain(reconciliationReportRepository.save(toEntity(report)));
    }

    private PaymentOrderEntity toEntity(PaymentOrder payment, PaymentOrderEntity existing) {
        PaymentOrderEntity entity = existing == null ? new PaymentOrderEntity() : existing;
        entity.setId(payment.id());
        entity.setPaymentNo(payment.paymentNo());
        entity.setOrderId(payment.orderId());
        entity.setUserId(payment.userId());
        entity.setMethod(payment.method());
        entity.setAmount(payment.amount());
        entity.setPaidAmount(payment.paidAmount());
        entity.setRefundedAmount(payment.refundedAmount());
        entity.setStatus(payment.status());
        entity.setIdempotencyKey(payment.idempotencyKey());
        entity.setProviderTradeNo(payment.providerTradeNo());
        if (StringUtils.hasText(payment.bankCardNo())) {
            entity.setBankCardCiphertext(piiCryptoService.encrypt(payment.bankCardNo()));
            entity.setBankCardHmac(piiCryptoService.blindIndex(payment.bankCardNo()));
            entity.setBankCardLast4(payment.bankCardLast4());
        } else if (existing == null) {
            entity.setBankCardCiphertext(null);
            entity.setBankCardHmac(payment.bankCardBlindIndex());
            entity.setBankCardLast4(payment.bankCardLast4());
        }
        entity.setPaidAt(payment.paidAt());
        entity.setCreateTime(payment.createTime());
        entity.setUpdateTime(payment.updateTime());
        if (existing == null) {
            applyOperation(entity, PaymentOperationAttempt.legacy());
        }
        return entity;
    }

    private PaymentOrder toDomain(PaymentOrderEntity entity) {
        return new PaymentOrder(
                entity.getId(),
                entity.getPaymentNo(),
                entity.getOrderId(),
                entity.getUserId(),
                entity.getMethod(),
                entity.getAmount(),
                entity.getPaidAmount(),
                entity.getRefundedAmount(),
                entity.getStatus(),
                entity.getIdempotencyKey(),
                entity.getProviderTradeNo(),
                null,
                entity.getBankCardLast4(),
                entity.getBankCardHmac(),
                entity.getPaidAt(),
                entity.getCreateTime(),
                entity.getUpdateTime());
    }

    private PaymentIntent toPaymentIntent(PaymentOrderEntity entity) {
        PaymentResponseSnapshot snapshot = entity.getResponseStatus() == null
                ? null
                : new PaymentResponseSnapshot(
                        entity.getResponsePaidAmount(),
                        entity.getResponseRefundedAmount(),
                        entity.getResponseStatus(),
                        entity.getResponseProviderTradeNo(),
                        entity.getPaymentUrl(),
                        entity.getResponsePaidAt());
        return new PaymentIntent(
                toDomain(entity),
                entity.getRequestFingerprint(),
                toOperation(entity),
                entity.getMerchantToken(),
                snapshot);
    }

    private static PaymentLedgerEntity toEntity(PaymentLedgerEntry ledger) {
        return toEntity(ledger, new PaymentLedgerEntity());
    }

    private static PaymentLedgerEntity toEntity(PaymentLedgerEntry ledger, PaymentLedgerEntity entity) {
        entity.setId(ledger.id());
        entity.setPaymentId(ledger.paymentId());
        entity.setOrderId(ledger.orderId());
        entity.setUserId(ledger.userId());
        entity.setLedgerType(ledger.type());
        entity.setAmount(ledger.amount());
        entity.setStatus(ledger.status());
        entity.setRequestKey(ledger.requestKey());
        entity.setProviderTradeNo(ledger.providerTradeNo());
        entity.setCreateTime(ledger.createTime());
        if (entity.getLastFailureClassification() == null) {
            entity.setAttemptCount(0);
            entity.setLeaseExpiresAt(null);
            entity.setLastFailureClassification(PaymentFailureClassification.NONE);
            entity.setAuditState(RefundAuditState.NONE);
            entity.setAuditIncludeOwner(false);
        }
        return entity;
    }

    private static PaymentLedgerEntry toDomain(PaymentLedgerEntity entity) {
        return new PaymentLedgerEntry(
                entity.getId(),
                entity.getPaymentId(),
                entity.getOrderId(),
                entity.getUserId(),
                entity.getLedgerType(),
                entity.getAmount(),
                entity.getStatus(),
                entity.getRequestKey(),
                entity.getProviderTradeNo(),
                entity.getCreateTime());
    }

    private static RefundRequest toRefundRequest(PaymentLedgerEntity entity) {
        RefundResponseSnapshot snapshot = entity.getResponsePaymentStatus() == null
                ? null
                : new RefundResponseSnapshot(
                        entity.getResponseRefundedAmount(),
                        entity.getResponsePaymentStatus(),
                        entity.getResponseLedgerStatus());
        return new RefundRequest(
                toDomain(entity),
                entity.getRequestFingerprint(),
                toOperation(entity),
                entity.getMerchantToken(),
                snapshot,
                toAuditIntent(entity));
    }

    private static void applyOperation(PaymentOrderEntity entity, PaymentOperationAttempt operation) {
        entity.setOperationState(operation.state());
        entity.setAttemptCount(operation.attemptCount());
        entity.setLeaseExpiresAt(operation.leaseExpiresAt());
        entity.setLastFailureClassification(operation.lastFailure());
    }

    private static void applyOperation(PaymentLedgerEntity entity, PaymentOperationAttempt operation) {
        entity.setOperationState(operation.state());
        entity.setAttemptCount(operation.attemptCount());
        entity.setLeaseExpiresAt(operation.leaseExpiresAt());
        entity.setLastFailureClassification(operation.lastFailure());
    }

    private static PaymentOperationAttempt toOperation(PaymentOrderEntity entity) {
        return new PaymentOperationAttempt(
                entity.getOperationState(),
                entity.getAttemptCount(),
                entity.getLeaseExpiresAt(),
                entity.getLastFailureClassification());
    }

    private static PaymentOperationAttempt toOperation(PaymentLedgerEntity entity) {
        if (entity.getOperationState() == null) {
            return PaymentOperationAttempt.legacy();
        }
        return new PaymentOperationAttempt(
                entity.getOperationState(),
                entity.getAttemptCount(),
                entity.getLeaseExpiresAt(),
                entity.getLastFailureClassification());
    }

    private static void applyAuditIntent(PaymentLedgerEntity entity, RefundAuditIntent auditIntent) {
        entity.setAuditState(auditIntent.state());
        entity.setAuditEventType(auditIntent.eventType());
        entity.setAuditActorUserId(auditIntent.actorUserId());
        entity.setAuditActorRole(auditIntent.actorRole());
        entity.setAuditSourceIp(auditIntent.sourceIp());
        entity.setAuditIncludeOwner(auditIntent.includeOwner());
        entity.setAuditDetail(auditIntent.detail());
    }

    private static RefundAuditIntent toAuditIntent(PaymentLedgerEntity entity) {
        if (entity.getAuditState() == null || RefundAuditState.NONE.equals(entity.getAuditState())) {
            return RefundAuditIntent.legacy();
        }
        return new RefundAuditIntent(
                entity.getAuditState(),
                entity.getAuditEventType(),
                entity.getAuditActorUserId(),
                entity.getAuditActorRole(),
                entity.getAuditSourceIp(),
                entity.isAuditIncludeOwner(),
                entity.getAuditDetail());
    }

    private static void applyResponseSnapshot(PaymentOrderEntity entity, PaymentResponseSnapshot responseSnapshot) {
        if (responseSnapshot == null) {
            return;
        }
        entity.setResponsePaidAmount(responseSnapshot.paidAmount());
        entity.setResponseRefundedAmount(responseSnapshot.refundedAmount());
        entity.setResponseStatus(responseSnapshot.status());
        entity.setResponseProviderTradeNo(responseSnapshot.providerTradeNo());
        entity.setPaymentUrl(responseSnapshot.paymentUrl());
        entity.setResponsePaidAt(responseSnapshot.paidAt());
    }

    private static void applyResponseSnapshot(PaymentLedgerEntity entity, RefundResponseSnapshot responseSnapshot) {
        if (responseSnapshot == null) {
            return;
        }
        entity.setResponseRefundedAmount(responseSnapshot.refundedAmount());
        entity.setResponsePaymentStatus(responseSnapshot.paymentStatus());
        entity.setResponseLedgerStatus(responseSnapshot.ledgerStatus());
    }

    private PaymentReconciliationReportEntity toEntity(PaymentReconciliationReport report) {
        PaymentReconciliationReportEntity entity = reconciliationReportRepository
                .findByProviderAndReportDate(report.provider(), report.reportDate())
                .orElseGet(PaymentReconciliationReportEntity::new);
        if (entity.getId() == null) {
            entity.setId(report.id());
        }
        entity.setProvider(report.provider());
        entity.setReportDate(report.reportDate());
        entity.setPlatformAmount(report.platformAmount());
        entity.setProviderAmount(report.providerAmount());
        entity.setDiffAmount(report.diffAmount());
        entity.setIssueCount(report.issueCount());
        entity.setStatus(report.status());
        entity.setEncryptedReportPayload(piiCryptoService.encrypt(report.reportPayload()));
        entity.setCreateTime(report.createTime());
        return entity;
    }

    private PaymentReconciliationReport toDomain(PaymentReconciliationReportEntity entity) {
        return new PaymentReconciliationReport(
                entity.getId(),
                entity.getProvider(),
                entity.getReportDate(),
                entity.getPlatformAmount(),
                entity.getProviderAmount(),
                entity.getDiffAmount(),
                entity.getIssueCount(),
                entity.getStatus(),
                piiCryptoService.decrypt(entity.getEncryptedReportPayload()),
                entity.getCreateTime());
    }
}
