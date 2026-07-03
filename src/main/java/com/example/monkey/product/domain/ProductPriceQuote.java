package com.example.monkey.product.domain;

import java.math.BigDecimal;

public record ProductPriceQuote(BigDecimal salePrice, BigDecimal strikePrice, String strategy) {}
