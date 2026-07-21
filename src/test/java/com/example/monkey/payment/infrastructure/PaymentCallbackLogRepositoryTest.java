package com.example.monkey.payment.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.monkey.payment.domain.PaymentMethod;
import com.example.monkey.shared.application.tenant.TenantContext;
import com.example.monkey.shared.infrastructure.privacy.PiiCryptoService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@DataJpaTest(showSql = false)
@ActiveProfiles("test")
@MockitoBean(types = PiiCryptoService.class)
class PaymentCallbackLogRepositoryTest {

    private final PaymentCallbackLogRepository repository;

    @Autowired
    PaymentCallbackLogRepositoryTest(PaymentCallbackLogRepository repository) {
        this.repository = repository;
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void reservationIsAtomicAndScopedByTenant() {
        TenantContext.setTenantId(7L);

        int first = repository.reserve(100L, PaymentMethod.WECHAT, "PAY100", "cb-1");
        int replay = repository.reserve(101L, PaymentMethod.WECHAT, "PAY100", "cb-1");

        assertThat(first).isEqualTo(1);
        assertThat(replay).isZero();
        assertThat(repository.findByProviderAndCallbackId(PaymentMethod.WECHAT, "cb-1"))
                .get()
                .extracting(PaymentCallbackLogEntity::getPaymentNo)
                .isEqualTo("PAY100");

        TenantContext.setTenantId(8L);

        assertThat(repository.reserve(102L, PaymentMethod.WECHAT, "PAY200", "cb-1"))
                .isEqualTo(1);
        assertThat(repository.findByProviderAndCallbackId(PaymentMethod.WECHAT, "cb-1"))
                .get()
                .extracting(PaymentCallbackLogEntity::getPaymentNo)
                .isEqualTo("PAY200");
    }
}
