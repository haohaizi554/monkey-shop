package com.example.monkey.payment.application.dto;

import com.example.monkey.payment.domain.PaymentMethod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

public record PaymentReconciliationRequestDto(
        @NotNull PaymentMethod provider,
        @NotNull LocalDate reportDate,
        @Size(max = 1000) List<@Valid ReconciliationLineDto> lines) {

    public PaymentReconciliationRequestDto {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}
