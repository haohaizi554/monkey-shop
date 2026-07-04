package com.example.monkey.membership.application.dto;

import jakarta.validation.constraints.NotNull;

public record BrowseRecordRequestDto(@NotNull Long productId) {}
