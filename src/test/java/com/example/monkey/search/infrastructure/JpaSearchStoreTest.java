package com.example.monkey.search.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.monkey.search.domain.PurchasedProduct;
import com.example.monkey.search.domain.SearchPage;
import com.example.monkey.search.domain.SearchProduct;
import com.example.monkey.search.domain.SearchQuery;
import com.example.monkey.search.domain.SearchSort;
import com.example.monkey.shared.application.tenant.TenantContext;
import com.example.monkey.shared.infrastructure.privacy.PiiCryptoService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.mockito.ArgumentCaptor;
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

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void searchPushesFilteringCountingPagingAndStockAggregationToTheDatabase() {
        TenantContext.setTenantId(42L);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);

        SearchPage page = store.search(new SearchQuery(
                "phone",
                11L,
                Map.of("colour", "gold"),
                SearchSort.PRICE_ASC,
                51,
                10,
                new BigDecimal("100.00"),
                new BigDecimal("200.00"),
                true));

        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isZero();
        verifyNoInteractions(productSpuRepository);

        ArgumentCaptor<String> contentSqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> contentParameterCaptor = ArgumentCaptor.forClass(Object[].class);
        ArgumentCaptor<String> countSqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> countParameterCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(contentSqlCaptor.capture(), any(RowMapper.class), contentParameterCaptor.capture());
        verify(jdbcTemplate).queryForObject(countSqlCaptor.capture(), eq(Long.class), countParameterCaptor.capture());
        assertThat(contentParameterCaptor.getValue())
                .containsExactly(
                        "phone",
                        "phone",
                        "phone",
                        42L,
                        42L,
                        42L,
                        "phone",
                        "phone",
                        "phone",
                        11L,
                        "colour",
                        "colour",
                        "gold",
                        new BigDecimal("100.00"),
                        new BigDecimal("200.00"),
                        10,
                        510L);
        assertThat(countParameterCaptor.getValue())
                .containsExactly(
                        42L,
                        42L,
                        42L,
                        "phone",
                        "phone",
                        "phone",
                        11L,
                        "colour",
                        "colour",
                        "gold",
                        new BigDecimal("100.00"),
                        new BigDecimal("200.00"));
        String sql = (contentSqlCaptor.getValue() + "\n" + countSqlCaptor.getValue()).toLowerCase();
        assertThat(sql)
                .contains("p.status = 'listed'")
                .contains("coalesce(p.member_price, p.original_price) >= ?")
                .contains("coalesce(p.member_price, p.original_price) <= ?")
                .contains("order by coalesce(p.member_price, p.original_price) asc, p.id desc")
                .contains("coalesce(stock.available_stock, 0) > 0")
                .contains("sku.tenant_id = ?")
                .contains("stock.tenant_id = sku.tenant_id")
                .contains("warehouse.tenant_id = sku.tenant_id")
                .contains("sku.active = true")
                .contains("warehouse.active = true")
                .contains("limit ? offset ?")
                .doesNotContain("limit 500");
    }

    @Test
    void searchReturnsUnsafeSnowflakeIdAndAggregateStockFromTheDatabaseRow() throws Exception {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    RowMapper<SearchProduct> rowMapper = (RowMapper<SearchProduct>) invocation.getArgument(1);
                    ResultSet resultSet = mock();
                    when(resultSet.getLong("product_id")).thenReturn(Long.MAX_VALUE);
                    when(resultSet.getLong("category_id")).thenReturn(11L);
                    when(resultSet.getString("name")).thenReturn("Phone");
                    when(resultSet.getString("title")).thenReturn("Smart phone");
                    when(resultSet.getString("image_url")).thenReturn("/phone.png");
                    when(resultSet.getBigDecimal("original_price")).thenReturn(new BigDecimal("999.00"));
                    when(resultSet.getBigDecimal("member_price")).thenReturn(new BigDecimal("899.00"));
                    when(resultSet.getString("attributes_json")).thenReturn("{\"tag\":\"phone\"}");
                    when(resultSet.getInt("stock")).thenReturn(7);
                    when(resultSet.getInt("score")).thenReturn(130);
                    return List.of(rowMapper.mapRow(resultSet, 0));
                });
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);

        SearchPage page = store.search(new SearchQuery("phone", null, Map.of(), SearchSort.RELEVANCE, 0, 10));

        assertThat(page.content()).hasSize(1);
        assertThat(page.content().get(0).productId()).isEqualTo(Long.MAX_VALUE);
        assertThat(page.content().get(0).stock()).isEqualTo(7);
        assertThat(page.totalElements()).isEqualTo(1L);
    }

    @Test
    void recentPurchasesReadOrderSnapshotThroughTenantScopedJdbcQuery() {
        PurchasedProduct purchase =
                new PurchasedProduct(7L, "Monkey Phone", LocalDateTime.parse("2026-07-04T12:00:00"));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(purchase));

        List<PurchasedProduct> result = store.recentPurchases(42L, 3);

        assertThat(result).containsExactly(purchase);
        verify(jdbcTemplate).query(anyString(), any(RowMapper.class), any(Object[].class));
    }
}
