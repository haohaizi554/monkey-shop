package com.example.monkey.order.domain;

import com.example.monkey.order.domain.OrderStore.ProductRecord;
import java.util.Optional;

public interface OrderProductPort {

    Optional<ProductRecord> findProductById(Long productId);

    boolean deductProductStock(Long productId);

    boolean restoreProductStock(Long productId);
}
