package com.example.monkey.logistics.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record FreightTemplate(
        Long id,
        LogisticsCarrier carrier,
        String province,
        FreightChargeMode chargeMode,
        BigDecimal baseWeightKg,
        BigDecimal baseFee,
        BigDecimal stepWeightKg,
        BigDecimal stepFee,
        BigDecimal itemFee,
        BigDecimal regionFee,
        int etaHours,
        boolean active) {

    public boolean appliesTo(String requestedProvince) {
        if (!active || carrier == null || chargeMode == null) {
            return false;
        }
        if ("*".equals(province)) {
            return true;
        }
        return normalize(province).equals(normalize(requestedProvince));
    }

    public boolean isMoreSpecificThan(FreightTemplate other) {
        return !"*".equals(province) && (other == null || "*".equals(other.province()));
    }

    public BigDecimal calculateWeight(BigDecimal weightKg) {
        BigDecimal base = safe(baseWeightKg, BigDecimal.ONE);
        BigDecimal extra = weightKg.subtract(base);
        BigDecimal steps = extra.compareTo(BigDecimal.ZERO) <= 0
                ? BigDecimal.ZERO
                : extra.divide(safe(stepWeightKg, BigDecimal.ONE), 0, RoundingMode.CEILING);
        return safe(baseFee, BigDecimal.ZERO).add(steps.multiply(safe(stepFee, BigDecimal.ZERO)));
    }

    public BigDecimal calculateItems(int itemCount) {
        return safe(itemFee, BigDecimal.ZERO).multiply(BigDecimal.valueOf(Math.max(1, itemCount)));
    }

    private static BigDecimal safe(BigDecimal value, BigDecimal fallback) {
        return value == null ? fallback : value;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }
}
