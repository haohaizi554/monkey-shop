package com.example.monkey.search.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.monkey.search.application.SearchDtoAssembler;
import com.example.monkey.search.application.dto.SearchProductDto;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SearchProductContractTest {

    @Test
    void exposesAggregatePurchasableStockWithoutConvertingSnowflakeIdsToNumbers() {
        String unsafeId = "9223372036854775807";
        SearchProduct product = new SearchProduct(
                Long.valueOf(unsafeId),
                9L,
                "Phone",
                "Gold",
                "/phone.png",
                new BigDecimal("20.00"),
                null,
                Map.of(),
                7,
                90);

        assertThat(product.productId()).isEqualTo(Long.MAX_VALUE);
        assertThat(product.stock()).isEqualTo(7);

        SearchProductDto dto = SearchDtoAssembler.toProduct(product);
        assertThat(dto.stock()).isEqualTo(7);
    }
}
