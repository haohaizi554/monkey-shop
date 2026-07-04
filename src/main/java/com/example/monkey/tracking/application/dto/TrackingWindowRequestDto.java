package com.example.monkey.tracking.application.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record TrackingWindowRequestDto(@Min(1) @Max(1440) Integer minutes) {}
