package com.example.monkey.assembler;

import com.example.monkey.domain.product.ProductCatalog.ProductRecord;
import com.example.monkey.dto.MonkeyRequestDto;
import com.example.monkey.dto.MonkeyResponseDto;
import com.example.monkey.entity.Monkey;

public final class MonkeyDtoAssembler {

    private MonkeyDtoAssembler() {}

    public static Monkey toEntity(MonkeyRequestDto request) {
        return new Monkey(
                request.id(),
                request.name(),
                request.breed(),
                request.price(),
                request.description(),
                request.imageUrl(),
                request.stock());
    }

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

    public static MonkeyResponseDto toResponse(Monkey monkey) {
        return new MonkeyResponseDto(
                monkey.getId(),
                monkey.getName(),
                monkey.getBreed(),
                monkey.getPrice(),
                monkey.getDescription(),
                monkey.getImageUrl(),
                monkey.getStock());
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
