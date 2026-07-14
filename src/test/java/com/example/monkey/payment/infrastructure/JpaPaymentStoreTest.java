package com.example.monkey.payment.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.payment.domain.PaymentLedgerEntry;
import com.example.monkey.payment.domain.PaymentLedgerStatus;
import com.example.monkey.payment.domain.PaymentLedgerType;
import com.example.monkey.payment.domain.PaymentMethod;
import com.example.monkey.payment.domain.PaymentOperationState;
import com.example.monkey.payment.domain.PaymentOrder;
import com.example.monkey.payment.domain.PaymentReconciliationReport;
import com.example.monkey.payment.domain.PaymentRequestFingerprint;
import com.example.monkey.payment.domain.PaymentResponseSnapshot;
import com.example.monkey.payment.domain.PaymentStatus;
import com.example.monkey.payment.domain.ReconciliationStatus;
import com.example.monkey.payment.domain.RefundResponseSnapshot;
import com.example.monkey.shared.infrastructure.privacy.PiiCryptoService;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.repository.Lock;

@ExtendWith(MockitoExtension.class)
class JpaPaymentStoreTest {

    @Mock
    private PaymentOrderRepository paymentOrderRepository;

    @Mock
    private PaymentLedgerRepository paymentLedgerRepository;

    @Mock
    private PaymentReconciliationReportRepository reconciliationReportRepository;

    @Mock
    private PiiCryptoService piiCryptoService;

    private JpaPaymentStore store;

    @BeforeEach
    void setUp() {
        store = new JpaPaymentStore(
                paymentOrderRepository, paymentLedgerRepository, reconciliationReportRepository, piiCryptoService);
    }

    @Test
    void savePaymentEncryptsBankCardAndStoresBlindIndex() {
        when(piiCryptoService.encrypt("6222026006705354210")).thenReturn("encrypted-card");
        when(piiCryptoService.blindIndex("6222026006705354210")).thenReturn("card-hmac");
        when(paymentOrderRepository.save(any(PaymentOrderEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        store.savePayment(bankCardPayment());

        PaymentOrderEntity entity = capturePayment();
        assertThat(entity.getBankCardCiphertext()).isEqualTo("encrypted-card");
        assertThat(entity.getBankCardHmac()).isEqualTo("card-hmac");
        assertThat(entity.getBankCardLast4()).isEqualTo("4210");
    }

    @Test
    void savePaymentStoresRequestFingerprintAndReplayUrl() {
        when(paymentOrderRepository.saveAndFlush(any(PaymentOrderEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        PaymentRequestFingerprint fingerprint =
                PaymentRequestFingerprint.of(10L, PaymentMethod.BANK_CARD, new BigDecimal("100.00"), "CNY");

        PaymentOrder payment = bankCardPayment();
        store.savePayment(
                payment,
                fingerprint.value(),
                PaymentOperationState.COMPLETED,
                payment.paymentNo(),
                PaymentResponseSnapshot.capture(payment, "/payments/PAY100"));

        PaymentOrderEntity entity = captureFlushedPayment();
        assertThat(entity.getRequestFingerprint()).isEqualTo(fingerprint.value());
        assertThat(entity.getPaymentUrl()).isEqualTo("/payments/PAY100");
    }

    @Test
    void saveLedgerMapsRefundLedgerWithoutCrossingDomainBoundary() {
        when(paymentLedgerRepository.save(any(PaymentLedgerEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        PaymentLedgerEntry saved = store.saveLedger(new PaymentLedgerEntry(
                200L,
                100L,
                10L,
                42L,
                PaymentLedgerType.REFUND,
                new BigDecimal("30.00"),
                PaymentLedgerStatus.SUCCESS,
                "refund-key",
                "refund-trade-1",
                LocalDateTime.parse("2026-07-04T09:00:00")));

        assertThat(saved.id()).isEqualTo(200L);
        assertThat(saved.type()).isEqualTo(PaymentLedgerType.REFUND);
        assertThat(captureLedger().getRequestKey()).isEqualTo("refund-key");
    }

    @Test
    void saveRefundLedgerStoresRequestFingerprint() {
        when(paymentLedgerRepository.save(any(PaymentLedgerEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        PaymentRequestFingerprint fingerprint =
                PaymentRequestFingerprint.ofRefund(100L, new BigDecimal("30.00"), "damaged item");

        PaymentLedgerEntry ledger = new PaymentLedgerEntry(
                200L,
                100L,
                10L,
                42L,
                PaymentLedgerType.REFUND,
                new BigDecimal("30.00"),
                PaymentLedgerStatus.SUCCESS,
                "refund-key",
                "refund-trade-1",
                LocalDateTime.parse("2026-07-04T09:00:00"));
        store.saveLedger(
                ledger,
                fingerprint.value(),
                PaymentOperationState.COMPLETED,
                "PAY100:refund:200",
                new RefundResponseSnapshot(
                        new BigDecimal("30.00"), PaymentStatus.PARTIALLY_REFUNDED, PaymentLedgerStatus.SUCCESS));

        assertThat(captureLedger().getRequestFingerprint()).isEqualTo(fingerprint.value());
    }

    @Test
    void withLockedPaymentUsesPessimisticWriteQuery() throws NoSuchMethodException {
        when(paymentOrderRepository.findByPaymentNoForUpdate("PAY100")).thenReturn(Optional.of(paidPaymentEntity()));

        Optional<BigDecimal> refundableAmount = store.withLockedPayment("PAY100", PaymentOrder::refundableAmount);

        assertThat(refundableAmount).contains(new BigDecimal("100.00"));
        verify(paymentOrderRepository).findByPaymentNoForUpdate("PAY100");
        Lock lock = PaymentOrderRepository.class
                .getMethod("findByPaymentNoForUpdate", String.class)
                .getAnnotation(Lock.class);
        assertThat(lock).isNotNull();
        assertThat(lock.value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
    }

    @Test
    void saveReportEncryptsPayloadAndPreservesExistingReportId() {
        PaymentReconciliationReportEntity existing = new PaymentReconciliationReportEntity();
        existing.setId(900L);
        existing.setProvider(PaymentMethod.WECHAT);
        existing.setReportDate(LocalDate.parse("2026-07-03"));
        when(reconciliationReportRepository.findByProviderAndReportDate(
                        PaymentMethod.WECHAT, LocalDate.parse("2026-07-03")))
                .thenReturn(Optional.of(existing));
        when(piiCryptoService.encrypt("payload")).thenReturn("encrypted-report");
        when(piiCryptoService.decrypt("encrypted-report")).thenReturn("payload");
        when(reconciliationReportRepository.save(any(PaymentReconciliationReportEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        PaymentReconciliationReport saved = store.saveReport(new PaymentReconciliationReport(
                901L,
                PaymentMethod.WECHAT,
                LocalDate.parse("2026-07-03"),
                new BigDecimal("100.00"),
                new BigDecimal("99.00"),
                new BigDecimal("1.00"),
                1,
                ReconciliationStatus.SUSPENDED,
                "payload",
                LocalDateTime.parse("2026-07-04T10:00:00")));

        PaymentReconciliationReportEntity entity = captureReport();
        assertThat(entity.getId()).isEqualTo(900L);
        assertThat(entity.getEncryptedReportPayload()).isEqualTo("encrypted-report");
        assertThat(saved.reportPayload()).isEqualTo("payload");
    }

    private PaymentOrderEntity capturePayment() {
        ArgumentCaptor<PaymentOrderEntity> captor = ArgumentCaptor.forClass(PaymentOrderEntity.class);
        verify(paymentOrderRepository).save(captor.capture());
        return captor.getValue();
    }

    private PaymentOrderEntity captureFlushedPayment() {
        ArgumentCaptor<PaymentOrderEntity> captor = ArgumentCaptor.forClass(PaymentOrderEntity.class);
        verify(paymentOrderRepository).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    private PaymentLedgerEntity captureLedger() {
        ArgumentCaptor<PaymentLedgerEntity> captor = ArgumentCaptor.forClass(PaymentLedgerEntity.class);
        verify(paymentLedgerRepository).save(captor.capture());
        return captor.getValue();
    }

    private PaymentReconciliationReportEntity captureReport() {
        ArgumentCaptor<PaymentReconciliationReportEntity> captor =
                ArgumentCaptor.forClass(PaymentReconciliationReportEntity.class);
        verify(reconciliationReportRepository).save(captor.capture());
        return captor.getValue();
    }

    private static PaymentOrder bankCardPayment() {
        return new PaymentOrder(
                100L,
                "PAY100",
                10L,
                42L,
                PaymentMethod.BANK_CARD,
                new BigDecimal("100.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                PaymentStatus.PENDING,
                "pay-key",
                "trade-1",
                "6222026006705354210",
                "4210",
                null,
                null,
                LocalDateTime.parse("2026-07-04T08:00:00"),
                LocalDateTime.parse("2026-07-04T08:00:00"));
    }

    private static PaymentOrderEntity paidPaymentEntity() {
        PaymentOrderEntity entity = new PaymentOrderEntity();
        entity.setId(100L);
        entity.setPaymentNo("PAY100");
        entity.setOrderId(10L);
        entity.setUserId(42L);
        entity.setMethod(PaymentMethod.WECHAT);
        entity.setAmount(new BigDecimal("100.00"));
        entity.setPaidAmount(new BigDecimal("100.00"));
        entity.setRefundedAmount(BigDecimal.ZERO);
        entity.setStatus(PaymentStatus.PAID);
        entity.setIdempotencyKey("pay-key");
        entity.setProviderTradeNo("wx-trade-1");
        entity.setPaidAt(LocalDateTime.parse("2026-07-04T08:10:00"));
        entity.setCreateTime(LocalDateTime.parse("2026-07-04T08:00:00"));
        entity.setUpdateTime(LocalDateTime.parse("2026-07-04T08:10:00"));
        entity.setVersion(0L);
        return entity;
    }
}
