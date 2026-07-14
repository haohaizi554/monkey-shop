package com.example.monkey.payment.infrastructure;

import com.example.monkey.payment.domain.PaymentLedgerEntry;
import com.example.monkey.payment.domain.PaymentLedgerType;
import com.example.monkey.payment.domain.PaymentMethod;
import com.example.monkey.payment.domain.PaymentOrder;
import com.example.monkey.payment.domain.PaymentReconciliationReport;
import com.example.monkey.payment.domain.PaymentStatus;
import com.example.monkey.payment.domain.PaymentStore;
import com.example.monkey.shared.infrastructure.privacy.PiiCryptoService;
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
    public PaymentOrder savePayment(PaymentOrder payment) {
        PaymentOrderEntity existing = payment.id() == null
                ? null
                : paymentOrderRepository.findById(payment.id()).orElse(null);
        PaymentOrderEntity saved = paymentOrderRepository.save(toEntity(payment, existing));
        return toDomain(saved);
    }

    @Override
    public PaymentIntent savePayment(PaymentOrder payment, String requestFingerprint, String paymentUrl) {
        PaymentOrderEntity existing = payment.id() == null
                ? null
                : paymentOrderRepository.findById(payment.id()).orElse(null);
        PaymentOrderEntity entity = toEntity(payment, existing);
        entity.setRequestFingerprint(requestFingerprint);
        entity.setPaymentUrl(paymentUrl);
        PaymentOrderEntity saved =
                existing == null ? paymentOrderRepository.saveAndFlush(entity) : paymentOrderRepository.save(entity);
        return toPaymentIntent(saved);
    }

    @Override
    public PaymentLedgerEntry saveLedger(PaymentLedgerEntry ledger) {
        return toDomain(paymentLedgerRepository.save(toEntity(ledger)));
    }

    @Override
    public RefundRequest saveLedger(PaymentLedgerEntry ledger, String requestFingerprint) {
        PaymentLedgerEntity entity = toEntity(ledger);
        entity.setRequestFingerprint(requestFingerprint);
        return toRefundRequest(paymentLedgerRepository.save(entity));
    }

    @Override
    public List<PaymentOrder> findPendingCreatedBefore(LocalDateTime cutoff, int limit) {
        return paymentOrderRepository
                .findByStatusAndCreateTimeBeforeOrderByCreateTimeAsc(
                        PaymentStatus.PENDING, cutoff, PageRequest.of(0, Math.max(1, limit)))
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
        return new PaymentIntent(toDomain(entity), entity.getRequestFingerprint(), entity.getPaymentUrl());
    }

    private static PaymentLedgerEntity toEntity(PaymentLedgerEntry ledger) {
        PaymentLedgerEntity entity = new PaymentLedgerEntity();
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
        return new RefundRequest(toDomain(entity), entity.getRequestFingerprint());
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
