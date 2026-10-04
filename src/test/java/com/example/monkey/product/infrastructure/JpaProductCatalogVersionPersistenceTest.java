package com.example.monkey.product.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.example.monkey.product.domain.ProductCatalog.ProductRecord;
import com.example.monkey.shared.domain.storage.ImageReferenceService;
import com.example.monkey.shared.infrastructure.privacy.PiiCryptoService;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@DataJpaTest(showSql = false)
@ActiveProfiles("test")
@MockitoBean(types = PiiCryptoService.class)
class JpaProductCatalogVersionPersistenceTest {

    private final TestEntityManager entityManager;
    private final MonkeyRepository monkeyRepository;
    private final JpaProductCatalog catalog;

    @Autowired
    JpaProductCatalogVersionPersistenceTest(TestEntityManager entityManager, MonkeyRepository monkeyRepository) {
        this.entityManager = entityManager;
        this.monkeyRepository = monkeyRepository;
        this.catalog = new JpaProductCatalog(monkeyRepository, mock(ImageReferenceService.class));
    }

    @Test
    void saveUpdatesExistingMonkeyInsteadOfPersistingDuplicateId() {
        Monkey existing = monkeyRepository.saveAndFlush(monkey(null, 5));
        Long id = existing.getId();
        entityManager.clear();

        assertThatCode(() -> {
                    catalog.save(record(id, 6));
                    entityManager.flush();
                })
                .doesNotThrowAnyException();

        entityManager.clear();
        Monkey saved = monkeyRepository.findById(id).orElseThrow();
        assertThat(saved.getStock()).isEqualTo(6);
        assertThat(saved.getVersion()).isEqualTo(1L);
    }

    @Test
    void saveNewMonkeyWithoutIdStillPersists() {
        ProductRecord saved = catalog.save(record(null, 4));
        entityManager.flush();

        assertThat(saved.id()).isNotNull();
        entityManager.clear();
        assertThat(monkeyRepository.findById(saved.id())).isPresent();
    }

    @Test
    void adapterUpdateKeepsOptimisticLockProtectionForStaleMonkeyEntity() {
        Monkey existing = monkeyRepository.saveAndFlush(monkey(null, 5));
        Long id = existing.getId();
        entityManager.clear();
        Monkey stale = monkeyRepository.findById(id).orElseThrow();
        entityManager.clear();

        catalog.save(record(id, 6));
        entityManager.flush();
        entityManager.clear();

        stale.setStock(7);
        assertThatThrownBy(() -> monkeyRepository.saveAndFlush(stale))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    private static Monkey monkey(Long id, int stock) {
        return new Monkey(id, "Momo", "Golden", BigDecimal.valueOf(199.99), "bright", null, stock);
    }

    private static ProductRecord record(Long id, int stock) {
        return new ProductRecord(id, "Momo", "Golden", BigDecimal.valueOf(199.99), "bright", null, stock);
    }
}
