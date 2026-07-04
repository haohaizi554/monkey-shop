package com.example.monkey.search.domain;

public record RecommendationItem(
        Long productId, String name, String title, String imageUrl, String reason, int score) {}
