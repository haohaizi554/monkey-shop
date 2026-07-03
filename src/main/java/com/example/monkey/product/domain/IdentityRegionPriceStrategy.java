package com.example.monkey.product.domain;

import java.math.BigDecimal;

public final class IdentityRegionPriceStrategy implements ProductPriceStrategy {

    @Override
    public ProductPriceQuote quote(ProductPriceBook priceBook, PriceContext context) {
        PriceContext safeContext = context == null ? new PriceContext("ANONYMOUS", "") : context;
        BigDecimal salePrice = priceBook.originalPrice();
        String strategy = "ORIGINAL";
        if (safeContext.region() != null && priceBook.regionPrices().containsKey(safeContext.region())) {
            salePrice = priceBook.regionPrices().get(safeContext.region());
            strategy = "REGION";
        }
        if (safeContext.isMember()
                && priceBook.memberPrice() != null
                && priceBook.memberPrice().compareTo(salePrice) < 0) {
            salePrice = priceBook.memberPrice();
            strategy = "MEMBER";
        }
        return new ProductPriceQuote(salePrice, priceBook.strikePrice(), strategy);
    }
}
