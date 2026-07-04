package com.example.monkey.search.domain;

import java.time.LocalDateTime;

public record PurchasedProduct(Long productId, String productName, LocalDateTime purchasedAt) {}
