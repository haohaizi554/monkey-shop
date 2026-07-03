package com.example.monkey.cart.infrastructure;

import com.example.monkey.cart.domain.CartCatalogReader;
import com.example.monkey.cart.domain.CartSkuSnapshot;
import com.example.monkey.product.domain.ProductStatus;
import com.example.monkey.product.infrastructure.ProductSku;
import com.example.monkey.product.infrastructure.ProductSkuRepository;
import com.example.monkey.product.infrastructure.ProductSpu;
import com.example.monkey.product.infrastructure.ProductSpuRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.cart.catalog-reader.provider", havingValue = "jpa", matchIfMissing = true)
public class JpaCartCatalogReader implements CartCatalogReader {

    private final ProductSkuRepository skuRepository;
    private final ProductSpuRepository spuRepository;

    public JpaCartCatalogReader(ProductSkuRepository skuRepository, ProductSpuRepository spuRepository) {
        this.skuRepository = skuRepository;
        this.spuRepository = spuRepository;
    }

    @Override
    public Optional<CartSkuSnapshot> findActiveSku(Long skuId) {
        return skuRepository
                .findById(skuId)
                .filter(ProductSku::isActive)
                .flatMap(sku -> spuRepository.findById(sku.getSpuId()).map(spu -> toSnapshot(sku, spu)))
                .filter(snapshot -> snapshot.salePrice().compareTo(BigDecimal.ZERO) >= 0);
    }

    private static CartSkuSnapshot toSnapshot(ProductSku sku, ProductSpu spu) {
        if (!ProductStatus.LISTED.equals(spu.getStatus())) {
            return null;
        }
        BigDecimal salePrice = sku.getMemberPrice() == null ? sku.getOriginalPrice() : sku.getMemberPrice();
        return new CartSkuSnapshot(
                sku.getId(),
                sku.getSpuId(),
                spu.getCategoryId(),
                sku.getSkuCode(),
                spu.getTitle() == null ? spu.getName() : spu.getTitle(),
                spu.getImageUrl(),
                salePrice);
    }
}
