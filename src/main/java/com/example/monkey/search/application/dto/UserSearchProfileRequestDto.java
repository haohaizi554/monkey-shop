package com.example.monkey.search.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public record UserSearchProfileRequestDto(
        @NotBlank @Size(max = 255) String interestProfile,
        @Size(max = 20) List<@Size(max = 32) String> tags) {}
