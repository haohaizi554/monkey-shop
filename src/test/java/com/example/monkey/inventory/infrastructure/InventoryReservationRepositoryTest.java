package com.example.monkey.inventory.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.monkey.shared.application.tenant.TenantContext;
import com.example.monkey.shared.infrastructure.privacy.PiiCryptoService;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@DataJpaTest(showSql = false)
@ActiveProfiles("test")
@MockitoBean(types = PiiCryptoService.class)
class InventoryReservationRepositoryTest {

    private final InventoryReservationRepository repository;

    @Autowired
    InventoryReservationRepositoryTest(InventoryReservationRepository repository) {
        this.repository = repository;
    }

    @Test
    void insertIfAbsentUsesTenantScopedReservationKeyClaim() {
        LocalDateTime expiresAt = LocalDateTime.of(2026, 7, 20, 12, 15);
        String firstFingerprint = "a".repeat(64);
        String secondFingerprint = "b".repeat(64);

        int first = repository.insertIfAbsent(
                9001L, 1L, "shared-key", firstFingerprint, 101L, 2200000000001L, 1L, 1, "RESERVED", expiresAt);
        int sameTenantReplay = repository.insertIfAbsent(
                9002L, 1L, "shared-key", secondFingerprint, 101L, 2200000000001L, 2L, 2, "RESERVED", expiresAt);
        int otherTenant = repository.insertIfAbsent(
                9003L, 2L, "shared-key", secondFingerprint, 101L, 2200000000001L, 3L, 2, "RESERVED", expiresAt);

        assertThat(first).isOne();
        assertThat(sameTenantReplay).isZero();
        assertThat(otherTenant).isOne();
        assertThat(repository.count()).isOne();
        assertThat(repository.findByTenantIdAndReservationKey(1L, "shared-key"))
                .get()
                .extracting(InventoryReservationEntity::getRequestFingerprint)
                .isEqualTo(firstFingerprint);
        try {
            TenantContext.setTenantId(2L);
            assertThat(repository.count()).isOne();
        } finally {
            TenantContext.clear();
        }
    }
}
