package com.example.monkey.search.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.monkey.search.application.dto.SearchProductQueryRequestDto;
import com.example.monkey.search.domain.SearchSort;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class SearchProductQueryRequestDtoTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void mapsPriceAndStockFiltersToTheSearchQuery() {
        SearchProductQueryRequestDto request = new SearchProductQueryRequestDto(
                "phone",
                9L,
                "colour",
                "gold",
                SearchSort.PRICE_ASC,
                2,
                10,
                new BigDecimal("10.00"),
                new BigDecimal("20.00"),
                true);

        assertThat(validator.validate(request)).isEmpty();
        assertThat(request.toQuery().minPrice()).isEqualByComparingTo("10.00");
        assertThat(request.toQuery().maxPrice()).isEqualByComparingTo("20.00");
        assertThat(request.toQuery().inStock()).isTrue();
    }

    @Test
    void rejectsNegativePriceBoundsAndAnInvertedRange() {
        SearchProductQueryRequestDto negative = new SearchProductQueryRequestDto(
                null, null, null, null, null, 0, 20, new BigDecimal("-0.01"), null, null);
        SearchProductQueryRequestDto negativeMax = new SearchProductQueryRequestDto(
                null, null, null, null, null, 0, 20, null, new BigDecimal("-0.01"), null);
        SearchProductQueryRequestDto inverted = new SearchProductQueryRequestDto(
                null, null, null, null, null, 0, 20, new BigDecimal("20.01"), new BigDecimal("20.00"), null);

        assertThat(validator.validate(negative))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("minPrice");
        assertThat(validator.validate(negativeMax))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("maxPrice");
        assertThat(validator.validate(inverted))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("priceRangeValid");
        assertThatThrownBy(inverted::toQuery)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("minPrice must not exceed maxPrice");
    }

    @Test
    void keepsInclusivePriceBoundaries() {
        SearchProductQueryRequestDto request = new SearchProductQueryRequestDto(
                null,
                null,
                null,
                null,
                null,
                0,
                20,
                new BigDecimal("0.00"),
                new BigDecimal("0.00"),
                false);

        assertThat(validator.validate(request)).isEmpty();
        assertThat(request.toQuery().minPrice()).isZero();
        assertThat(request.toQuery().maxPrice()).isZero();
    }
}
