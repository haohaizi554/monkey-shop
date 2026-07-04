package com.example.monkey.search.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.product.domain.ProductStatus;
import com.example.monkey.search.domain.PurchasedProduct;
import com.example.monkey.search.domain.SearchPage;
import com.example.monkey.search.domain.SearchProduct;
import com.example.monkey.search.domain.SearchQuery;
import com.example.monkey.search.domain.SearchSort;
import com.example.monkey.shared.infrastructure.privacy.PiiCryptoService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JpaSearchStoreTest {

    private final JdbcTemplate jdbcTemplate = mock();
    private final SearchProductSpuRepository productSpuRepository = mock();
    private final JpaSearchStore store = new JpaSearchStore(
            jdbcTemplate,
            productSpuRepository,
            mock(SearchHistoryRepository.class),
            mock(UserSearchProfileRepository.class),
            mock(PiiCryptoService.class),
            new ObjectMapper().findAndRegisterModules());

    @Test
    void searchReadsCatalogThroughSearchProjectionAndLegacyProductsThroughTenantScopedJdbcQuery() {
        SearchProductSpuEntity catalogEntity = new SearchProductSpuEntity(
                2001L,
                11L,
                "Phone",
                "Smart phone",
                ProductStatus.LISTED,
                new BigDecimal("999.00"),
                new BigDecimal("899.00"),
                "{\"tag\":\"phone\"}",
                "/phone.png");
        SearchProduct legacy = new SearchProduct(
                7L,
                null,
                "Monkey Phone",
                "Legacy phone",
                "/legacy.png",
                new BigDecimal("199.00"),
                null,
                Map.of("breed", "phone", "stock", 3),
                80);
        when(productSpuRepository.findByStatusOrderByIdDesc(eq(ProductStatus.LISTED), any(Pageable.class)))
                .thenReturn(List.of(catalogEntity));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(1L), eq(500)))
                .thenReturn(List.of(legacy));

        SearchPage page = store.search(new SearchQuery("phone", null, Map.of(), SearchSort.RELEVANCE, 0, 10));

        assertThat(page.content()).extracting(SearchProduct::productId).containsExactly(2001L, 7L);
        verify(productSpuRepository).findByStatusOrderByIdDesc(eq(ProductStatus.LISTED), any(Pageable.class));
        verify(jdbcTemplate).query(anyString(), any(RowMapper.class), eq(1L), eq(500));
    }

    @Test
    void recentPurchasesReadOrderSnapshotThroughTenantScopedJdbcQuery() {
        PurchasedProduct purchase =
                new PurchasedProduct(7L, "Monkey Phone", LocalDateTime.parse("2026-07-04T12:00:00"));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(1L), eq(42L), eq(3)))
                .thenReturn(List.of(purchase));

        List<PurchasedProduct> result = store.recentPurchases(42L, 3);

        assertThat(result).containsExactly(purchase);
        verify(jdbcTemplate).query(anyString(), any(RowMapper.class), eq(1L), eq(42L), eq(3));
    }
}
