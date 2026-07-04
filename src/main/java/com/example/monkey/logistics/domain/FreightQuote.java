package com.example.monkey.logistics.domain;

import java.math.BigDecimal;
import java.util.List;

public record FreightQuote(
        LogisticsCarrier carrier,
        String province,
        BigDecimal weightKg,
        int itemCount,
        BigDecimal amount,
        int etaHours,
        List<FreightChargeMode> appliedModes) {

    public FreightQuote {
        appliedModes = appliedModes == null ? List.of() : List.copyOf(appliedModes);
    }
}
