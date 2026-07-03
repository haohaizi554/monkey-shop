package com.example.monkey.marketing.application.dto;

import java.math.BigDecimal;
import java.util.List;

public record MarketingPriceQuoteDto(
        BigDecimal originalAmount, BigDecimal discountAmount, BigDecimal payableAmount, List<String> appliedCoupons) {}
