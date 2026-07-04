package com.example.monkey.membership.application.dto;

import jakarta.validation.constraints.Min;

public record PointsRedeemRequestDto(@Min(1) long points, String referenceKey) {}
