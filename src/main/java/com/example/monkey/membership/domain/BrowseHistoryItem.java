package com.example.monkey.membership.domain;

import java.time.LocalDateTime;

public record BrowseHistoryItem(
        Long id,
        Long userId,
        Long productId,
        String productName,
        String productImage,
        LocalDateTime viewedAt,
        LocalDateTime expiresAt) {}
