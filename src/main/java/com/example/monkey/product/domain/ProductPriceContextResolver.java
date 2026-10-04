package com.example.monkey.product.domain;

public interface ProductPriceContextResolver {

    PriceContext resolve(Long userId, String region);
}
