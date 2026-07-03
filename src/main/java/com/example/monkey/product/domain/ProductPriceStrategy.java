package com.example.monkey.product.domain;

public interface ProductPriceStrategy {

    ProductPriceQuote quote(ProductPriceBook priceBook, PriceContext context);
}
