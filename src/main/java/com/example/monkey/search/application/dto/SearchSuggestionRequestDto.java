package com.example.monkey.search.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SearchSuggestionRequestDto(
        @NotBlank @Size(max = 128) String keyword) {}
