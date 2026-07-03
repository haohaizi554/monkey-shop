package com.example.monkey.product.application.dto;

import java.util.List;

public record CategoryNodeResponseDto(
        Long id, Long parentId, int level, String code, String name, List<CategoryNodeResponseDto> children) {}
