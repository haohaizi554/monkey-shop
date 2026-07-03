package com.example.monkey.cart.domain;

import java.util.Optional;

public interface CartCatalogReader {

    Optional<CartSkuSnapshot> findActiveSku(Long skuId);
}
