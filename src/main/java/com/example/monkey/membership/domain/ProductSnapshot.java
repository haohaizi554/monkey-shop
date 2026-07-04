package com.example.monkey.membership.domain;

import java.math.BigDecimal;

public record ProductSnapshot(Long id, String name, String imageUrl, BigDecimal price) {}
