package com.example.monkey.logistics.application.dto;

import com.example.monkey.logistics.domain.FreightChargeMode;
import com.example.monkey.logistics.domain.LogisticsCarrier;
import java.math.BigDecimal;
import java.util.List;

public record FreightQuoteResponseDto(
        LogisticsCarrier carrier,
        String province,
        BigDecimal weightKg,
        int itemCount,
        BigDecimal amount,
        int etaHours,
        List<FreightChargeMode> appliedModes) {

    public FreightQuoteResponseDto {
        appliedModes = appliedModes == null ? List.of() : List.copyOf(appliedModes);
    }
}
