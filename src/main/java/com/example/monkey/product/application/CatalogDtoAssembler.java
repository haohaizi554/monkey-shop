package com.example.monkey.product.application;

import com.example.monkey.product.application.dto.CatalogSkuResponseDto;
import com.example.monkey.product.application.dto.CatalogSpuResponseDto;
import com.example.monkey.product.application.dto.CategoryNodeResponseDto;
import com.example.monkey.product.domain.CatalogSku;
import com.example.monkey.product.domain.CatalogSpu;
import com.example.monkey.product.domain.CategoryNode;
import com.example.monkey.product.domain.ProductPriceBook;

final class CatalogDtoAssembler {

    private CatalogDtoAssembler() {}

    static CatalogSpuResponseDto toResponse(CatalogSpu spu) {
        ProductPriceBook priceBook = spu.priceBook();
        return new CatalogSpuResponseDto(
                spu.id(),
                spu.categoryId(),
                spu.name(),
                spu.title(),
                spu.status(),
                priceBook.originalPrice(),
                priceBook.memberPrice(),
                priceBook.strikePrice(),
                priceBook.regionPrices(),
                spu.attributes(),
                spu.detailJsonLd(),
                spu.imageUrl(),
                spu.skus().stream().map(CatalogDtoAssembler::toResponse).toList());
    }

    static CatalogSkuResponseDto toResponse(CatalogSku sku) {
        ProductPriceBook priceBook = sku.priceBook();
        return new CatalogSkuResponseDto(
                sku.id(),
                sku.spuId(),
                sku.skuCode(),
                sku.specification().values(),
                priceBook.originalPrice(),
                priceBook.memberPrice(),
                priceBook.strikePrice(),
                priceBook.regionPrices(),
                sku.active());
    }

    static CategoryNodeResponseDto toResponse(CategoryNode node) {
        return new CategoryNodeResponseDto(
                node.id(),
                node.parentId(),
                node.level(),
                node.code(),
                node.name(),
                node.children().stream().map(CatalogDtoAssembler::toResponse).toList());
    }
}
