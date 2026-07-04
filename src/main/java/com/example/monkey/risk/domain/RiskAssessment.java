package com.example.monkey.risk.domain;

import java.util.List;

public record RiskAssessment(int score, RiskDecision decision, List<RiskSignal> signals) {
    public RiskAssessment {
        score = Math.max(0, Math.min(100, score));
        signals = signals == null ? List.of() : List.copyOf(signals);
    }
}
