package com.example.monkey.shared.domain.observability;

public interface VisitLogRecorder {
    void recordVisit(String clientIp);
}
