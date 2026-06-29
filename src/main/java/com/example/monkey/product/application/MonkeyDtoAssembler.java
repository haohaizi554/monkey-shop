package com.example.monkey.product.application;

import com.example.monkey.product.application.dto.MonkeyRequestDto;
import com.example.monkey.product.application.dto.MonkeyResponseDto;
import com.example.monkey.product.domain.ProductCatalog.ProductRecord;

public final class MonkeyDtoAssembler {

    private MonkeyDtoAssembler() {}

    public static ProductRecord toProductRecord(MonkeyRequestDto request) {
        return toProductRecord(request, request.imageUrl());
    }

    public static ProductRecord toProductRecord(MonkeyRequestDto request, String imageUrl) {
        return new ProductRecord(
                request.id(),
                request.name(),
                request.breed(),
                request.price(),
                request.description(),
                imageUrl,
                request.stock());
    }

    public static MonkeyResponseDto toResponse(ProductRecord product) {
        return new MonkeyResponseDto(
                product.id(),
                product.name(),
                product.breed(),
                product.price(),
                product.description(),
                product.imageUrl(),
                product.stock());
    }
}
