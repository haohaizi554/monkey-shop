package com.example.monkey.search.application.dto;

public record RecommendationDto(Long productId, String name, String title, String imageUrl, String reason, int score) {}
