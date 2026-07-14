package com.example.monkey.payment.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.monkey.payment.domain.PaymentMethod;
import com.example.monkey.payment.domain.PaymentOperationState;
import com.example.monkey.payment.domain.PaymentStatus;
import com.example.monkey.shared.infrastructure.privacy.PiiCryptoService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@DataJpaTest(showSql = false)
@ActiveProfiles("test")
@MockitoBean(types = PiiCryptoService.class)
class PaymentReservationJpaIntegrationTest {

    private final TestEntityManager entityManager;
    private final PaymentOrderRepository paymentOrderRepository;

    @Autowired
    PaymentReservationJpaIntegrationTest(
            TestEntityManager entityManager, PaymentOrderRepository paymentOrderRepository) {
        this.entityManager = entityManager;
        this.paymentOrderRepository = paymentOrderRepository;
    }

    @Test
    void databaseRejectsSameTenantUserAndIdempotencyKey() {
        paymentOrderRepository.saveAndFlush(payment(100L, 1L, 10L, "PAY100", "same-key"));

        assertThatThrownBy(() -> paymentOrderRepository.saveAndFlush(payment(101L, 1L, 11L, "PAY101", "same-key")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sameUserAndIdempotencyKeyRemainIndependentAcrossTenants() {
        paymentOrderRepository.saveAndFlush(payment(100L, 1L, 10L, "PAY100", "same-key"));
        paymentOrderRepository.saveAndFlush(payment(101L, 2L, 11L, "PAY101", "same-key"));
        entityManager.clear();

        Number rows = (Number) entityManager
                .getEntityManager()
                .createNativeQuery("select count(*) from payment_order where id in (100, 101)")
                .getSingleResult();

        assertThat(rows.longValue()).isEqualTo(2L);
    }

    private static PaymentOrderEntity payment(
            Long id, Long tenantId, Long orderId, String paymentNo, String idempotencyKey) {
        LocalDateTime now = LocalDateTime.parse("2026-07-04T08:30:00");
        PaymentOrderEntity entity = new PaymentOrderEntity();
        entity.setId(id);
        entity.setTenantId(tenantId);
        entity.setPaymentNo(paymentNo);
        entity.setOrderId(orderId);
        entity.setUserId(42L);
        entity.setMethod(PaymentMethod.WECHAT);
        entity.setAmount(new BigDecimal("100.00"));
        entity.setPaidAmount(BigDecimal.ZERO.setScale(2));
        entity.setRefundedAmount(BigDecimal.ZERO.setScale(2));
        entity.setStatus(PaymentStatus.PENDING);
        entity.setIdempotencyKey(idempotencyKey);
        entity.setRequestFingerprint("a".repeat(64));
        entity.setOperationState(PaymentOperationState.RESERVED);
        entity.setMerchantToken(paymentNo);
        entity.setCreateTime(now);
        entity.setUpdateTime(now);
        return entity;
    }
}
