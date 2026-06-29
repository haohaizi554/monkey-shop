package com.example.monkey.product.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.product.domain.ProductCatalog.ProductPage;
import com.example.monkey.product.domain.ProductCatalog.ProductPageRequest;
import com.example.monkey.product.domain.ProductCatalog.ProductRecord;
import com.example.monkey.product.domain.ProductCatalog.SortOrder;
import com.example.monkey.product.domain.ProductCatalog.SortOrder.Direction;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

@ExtendWith(MockitoExtension.class)
class JpaProductCatalogTest {

    @Mock
    private MonkeyRepository monkeyRepository;

    private JpaProductCatalog catalog;

    @BeforeEach
    void setUp() {
        catalog = new JpaProductCatalog(monkeyRepository);
    }

    @Test
    void findPageMapsRepositoryPageAndPreservesSortOrders() {
        PageRequest repositoryPageable =
                PageRequest.of(1, 10, Sort.by(Sort.Order.desc("price"), Sort.Order.asc("name")));
        when(monkeyRepository.findAllBy(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(monkey()), repositoryPageable, 23));

        ProductPage result = catalog.findPage(new ProductPageRequest(
                1, 10, List.of(new SortOrder("price", Direction.DESC), new SortOrder("name", Direction.ASC))));

        assertThat(result.content()).containsExactly(record());
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.size()).isEqualTo(10);
        assertThat(result.totalElements()).isEqualTo(23);
        assertThat(result.totalPages()).isEqualTo(3);
        assertThat(result.first()).isFalse();
        assertThat(result.last()).isFalse();

        Pageable pageable = capturePageable();
        assertThat(pageable.getPageNumber()).isEqualTo(1);
        assertThat(pageable.getPageSize()).isEqualTo(10);
        assertThat(pageable.getSort().getOrderFor("price").getDirection()).isEqualTo(Sort.Direction.DESC);
        assertThat(pageable.getSort().getOrderFor("name").getDirection()).isEqualTo(Sort.Direction.ASC);
    }

    @Test
    void findPageUsesUnsortedPageableWhenNoSortOrdersAreProvided() {
        when(monkeyRepository.findAllBy(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 5), 0));

        ProductPage result = catalog.findPage(new ProductPageRequest(0, 5, null));

        assertThat(result.content()).isEmpty();
        Pageable pageable = capturePageable();
        assertThat(pageable.getSort().isUnsorted()).isTrue();
    }

    @Test
    void findPageFiltersUnsupportedSortPropertiesBeforeQueryingJpa() {
        when(monkeyRepository.findAllBy(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(monkey()), PageRequest.of(0, 10), 1));

        catalog.findPage(new ProductPageRequest(
                0, 10, List.of(new SortOrder("description", Direction.ASC), new SortOrder("price", Direction.DESC))));

        Pageable pageable = capturePageable();
        assertThat(pageable.getSort().getOrderFor("description")).isNull();
        assertThat(pageable.getSort().getOrderFor("price").getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void findPageBoundsPageAndSizeBeforeQueryingJpa() {
        when(monkeyRepository.findAllBy(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(monkey()), PageRequest.of(0, 100), 1));

        catalog.findPage(new ProductPageRequest(-1, 1000, List.of()));

        Pageable pageable = capturePageable();
        assertThat(pageable.getPageNumber()).isZero();
        assertThat(pageable.getPageSize()).isEqualTo(100);
    }

    @Test
    void saveMapsDomainRecordThroughRepositoryEntity() {
        when(monkeyRepository.save(any(Monkey.class))).thenReturn(monkey());

        ProductRecord result = catalog.save(record());

        assertThat(result).isEqualTo(record());
        Monkey savedMonkey = captureSavedMonkey();
        assertThat(savedMonkey.getId()).isEqualTo(7L);
        assertThat(savedMonkey.getName()).isEqualTo("Momo");
        assertThat(savedMonkey.getPrice()).isEqualByComparingTo("199.99");
    }

    @Test
    void findByIdMapsRepositoryOptional() {
        when(monkeyRepository.findById(7L)).thenReturn(Optional.of(monkey()));

        Optional<ProductRecord> result = catalog.findById(7L);

        assertThat(result).contains(record());
    }

    @Test
    void deleteByIdDelegatesToRepository() {
        catalog.deleteById(7L);

        verify(monkeyRepository).deleteById(7L);
    }

    @Test
    void productPageNormalizesNullContentToEmptyList() {
        ProductPage page = new ProductPage(null, 0, 5, 0, 0, true, true);

        assertThat(page.content()).isEmpty();
    }

    private Pageable capturePageable() {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(monkeyRepository).findAllBy(captor.capture());
        return captor.getValue();
    }

    private Monkey captureSavedMonkey() {
        ArgumentCaptor<Monkey> captor = ArgumentCaptor.forClass(Monkey.class);
        verify(monkeyRepository).save(captor.capture());
        return captor.getValue();
    }

    private static Monkey monkey() {
        return new Monkey(7L, "Momo", "Golden", BigDecimal.valueOf(199.99), "bright", "/images/momo.png", 5);
    }

    private static ProductRecord record() {
        return new ProductRecord(7L, "Momo", "Golden", BigDecimal.valueOf(199.99), "bright", "/images/momo.png", 5);
    }
}
