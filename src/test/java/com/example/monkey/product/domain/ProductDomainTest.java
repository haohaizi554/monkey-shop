package com.example.monkey.product.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProductDomainTest {

    @Test
    void skuCartesianProductGeneratesAllCombinations() {
        List<SkuSpecification> specifications = SkuCartesianProductGenerator.generate(List.of(
                new SpecificationDimension("color", List.of("red", "blue")),
                new SpecificationDimension("size", List.of("64g", "128g", "256g"))));

        assertThat(specifications).hasSize(6);
        assertThat(specifications)
                .extracting(SkuSpecification::values)
                .contains(Map.of("color", "red", "size", "64g"), Map.of("color", "blue", "size", "256g"));
    }

    @Test
    void productStateMachineRejectsIllegalTransition() {
        assertThat(ProductStateMachine.transition(ProductStatus.DRAFT, ProductStatus.PENDING_REVIEW))
                .isEqualTo(ProductStatus.PENDING_REVIEW);
        assertThatThrownBy(() -> ProductStateMachine.transition(ProductStatus.LISTED, ProductStatus.APPROVED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Illegal product status transition");
    }

    @Test
    void priceStrategyRoutesByIdentityAndRegion() {
        ProductPriceBook priceBook = new ProductPriceBook(
                new BigDecimal("100.00"),
                new BigDecimal("80.00"),
                new BigDecimal("129.00"),
                Map.of("CN-BJ", new BigDecimal("90.00")));
        IdentityRegionPriceStrategy strategy = new IdentityRegionPriceStrategy();

        ProductPriceQuote regionQuote = strategy.quote(priceBook, new PriceContext("ANONYMOUS", "CN-BJ"));
        ProductPriceQuote memberQuote = strategy.quote(priceBook, new PriceContext("PLUS", "CN-BJ"));
        ProductPriceQuote originalQuote = strategy.quote(priceBook, new PriceContext("ANONYMOUS", "CN-SH"));

        assertThat(regionQuote.salePrice()).isEqualByComparingTo("90.00");
        assertThat(regionQuote.strategy()).isEqualTo("REGION");
        assertThat(memberQuote.salePrice()).isEqualByComparingTo("80.00");
        assertThat(memberQuote.strategy()).isEqualTo("MEMBER");
        assertThat(originalQuote.salePrice()).isEqualByComparingTo("100.00");
        assertThat(originalQuote.strategy()).isEqualTo("ORIGINAL");
    }

    @Test
    void productPriceBookRejectsInvalidOptionalPrices() {
        assertThatThrownBy(() -> new ProductPriceBook(new BigDecimal("100.00"), BigDecimal.ZERO, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("member price must be positive");

        assertThatThrownBy(() -> new ProductPriceBook(new BigDecimal("100.00"), null, new BigDecimal("-1.00"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strike price must be positive");

        assertThatThrownBy(() ->
                        new ProductPriceBook(new BigDecimal("100.00"), null, null, Map.of("CN-BJ", BigDecimal.ZERO)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("region prices must be positive");
    }

    @Test
    void catalogSkuAndSpuFailClosedForMissingPriceAndSpecification() {
        ProductPriceBook priceBook = new ProductPriceBook(new BigDecimal("100.00"), null, null, null);
        SkuSpecification specification = new SkuSpecification(Map.of("color", "red"));

        assertThatThrownBy(() -> new CatalogSku(1L, 10L, "SKU-1", null, priceBook, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SKU specification is required");
        assertThatThrownBy(() -> new CatalogSku(1L, 10L, "SKU-1", specification, null, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SKU price book is required");
        assertThatThrownBy(() ->
                        new CatalogSpu(10L, 3L, "phone", "phone title", null, null, null, null, null, null, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SPU price book is required");
    }

    @Test
    void catalogSpuDefensivelyCopiesAttributesAndSkus() {
        ProductPriceBook priceBook = new ProductPriceBook(new BigDecimal("100.00"), null, null, null);
        CatalogSku sku =
                new CatalogSku(1L, 10L, "SKU-1", new SkuSpecification(Map.of("color", "red")), priceBook, true);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("brand", "Monkey");

        CatalogSpu spu = new CatalogSpu(
                10L, 3L, "phone", "phone title", null, priceBook, attributes, null, null, null, List.of(sku));
        attributes.put("brand", "Changed");

        assertThat(spu.status()).isEqualTo(ProductStatus.DRAFT);
        assertThat(spu.attributes()).containsEntry("brand", "Monkey");
        assertThatThrownBy(() -> spu.skus().add(sku)).isInstanceOf(UnsupportedOperationException.class);
    }
}
