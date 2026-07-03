package com.example.monkey.product.infrastructure;

import com.example.monkey.product.domain.IdentityRegionPriceStrategy;
import com.example.monkey.product.domain.ProductPriceStrategy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ProductCatalogConfiguration {

    @Bean
    @ConditionalOnMissingBean(ProductPriceStrategy.class)
    public ProductPriceStrategy productPriceStrategy() {
        return new IdentityRegionPriceStrategy();
    }
}
