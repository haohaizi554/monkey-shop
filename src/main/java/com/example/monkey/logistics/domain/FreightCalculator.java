package com.example.monkey.logistics.domain;

import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class FreightCalculator {

    public FreightQuote quote(
            LogisticsCarrier carrier,
            String province,
            BigDecimal weightKg,
            int itemCount,
            List<FreightTemplate> templates) {
        if (carrier == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "carrier is required");
        }
        if (weightKg == null || weightKg.compareTo(BigDecimal.ZERO) <= 0 || itemCount <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "weight and item count must be positive");
        }
        List<FreightTemplate> applicable = templates == null
                ? List.of()
                : templates.stream()
                        .filter(template -> template.carrier() == carrier)
                        .filter(template -> template.appliesTo(province))
                        .toList();
        if (applicable.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "freight template does not exist");
        }
        Map<FreightChargeMode, FreightTemplate> selected = new EnumMap<>(FreightChargeMode.class);
        for (FreightTemplate template : applicable) {
            FreightTemplate current = selected.get(template.chargeMode());
            if (current == null || template.isMoreSpecificThan(current)) {
                selected.put(template.chargeMode(), template);
            }
        }
        BigDecimal amount = BigDecimal.ZERO;
        if (selected.containsKey(FreightChargeMode.WEIGHT)) {
            amount = amount.add(selected.get(FreightChargeMode.WEIGHT).calculateWeight(weightKg));
        }
        if (selected.containsKey(FreightChargeMode.ITEM)) {
            amount = amount.add(selected.get(FreightChargeMode.ITEM).calculateItems(itemCount));
        }
        if (selected.containsKey(FreightChargeMode.REGION)) {
            amount = amount.add(selected.get(FreightChargeMode.REGION).regionFee());
        }
        int etaHours = selected.values().stream()
                .mapToInt(FreightTemplate::etaHours)
                .max()
                .orElse(48);
        List<FreightChargeMode> appliedModes = List.copyOf(selected.keySet());
        return new FreightQuote(
                carrier,
                province,
                money(weightKg),
                itemCount,
                amount.setScale(2, RoundingMode.HALF_UP),
                etaHours,
                appliedModes);
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
