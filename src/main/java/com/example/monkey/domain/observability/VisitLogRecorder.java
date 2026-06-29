package com.example.monkey.domain.observability;

public interface VisitLogRecorder {
    void recordVisit(String clientIp);
}
