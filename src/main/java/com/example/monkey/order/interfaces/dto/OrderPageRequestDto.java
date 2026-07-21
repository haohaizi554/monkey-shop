package com.example.monkey.order.interfaces.dto;

import jakarta.validation.constraints.Size;
import java.util.List;

public record OrderPageRequestDto(
        @Size(max = 16) List<@Size(max = 32) String> status,
        @Size(max = 128) String keyword) {

    public List<String> statuses() {
        return status == null ? List.of() : List.copyOf(status);
    }
}
