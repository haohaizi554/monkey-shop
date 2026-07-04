package com.example.monkey.membership.application.dto;

import java.time.LocalDateTime;

public record BrowseHistoryDto(
        Long productId, String productName, String productImage, LocalDateTime viewedAt, LocalDateTime expiresAt) {}
