package com.example.monkey.cart.domain;

import com.example.monkey.product.domain.PriceContext;
import java.util.Optional;

public interface CartCatalogReader {

    Optional<CartSkuSnapshot> findActiveSku(Long skuId, PriceContext priceContext);
}
