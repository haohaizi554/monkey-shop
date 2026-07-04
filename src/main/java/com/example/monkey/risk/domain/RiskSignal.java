package com.example.monkey.risk.domain;

public record RiskSignal(RiskSignalType type, int weight, String detail) {}
