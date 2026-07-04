package com.example.monkey.risk.application.dto;

import com.example.monkey.risk.domain.RiskSignalType;

public record RiskSignalDto(RiskSignalType type, int weight, String detail) {}
