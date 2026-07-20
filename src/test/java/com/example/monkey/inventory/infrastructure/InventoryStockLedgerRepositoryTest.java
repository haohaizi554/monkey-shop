package com.example.monkey.inventory.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

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
class InventoryStockLedgerRepositoryTest {

    private final InventoryStockLedgerRepository repository;

    @Autowired
    InventoryStockLedgerRepositoryTest(InventoryStockLedgerRepository repository) {
        this.repository = repository;
    }

    @Test
    void insertIfAbsentAtomicallyClaimsIdempotencyKeyAcrossStocks() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 20, 12, 0);

        int first = repository.insertIfAbsent(
                9001L, 101L, 2200000000001L, null, 1L, "COMPENSATE", 1, "shared-key", now, 1L);
        int replay = repository.insertIfAbsent(
                9002L, 202L, 2200000000002L, null, 2L, "COMPENSATE", 1, "shared-key", now, 1L);

        assertThat(first).isOne();
        assertThat(replay).isZero();
        assertThat(repository.count()).isOne();
    }
}
