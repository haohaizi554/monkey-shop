package com.example.monkey.infrastructure.observability;

import com.example.monkey.domain.observability.VisitLogRecorder;
import com.example.monkey.entity.VisitLog;
import com.example.monkey.repository.VisitLogRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
public final class JpaVisitLogRecorder implements VisitLogRecorder {

    private static final Logger log = LoggerFactory.getLogger(JpaVisitLogRecorder.class);

    private final VisitLogRepository visitLogRepository;
    private final Clock clock;

    public JpaVisitLogRecorder(VisitLogRepository visitLogRepository) {
        this(visitLogRepository, Clock.systemUTC());
    }

    JpaVisitLogRecorder(VisitLogRepository visitLogRepository, Clock clock) {
        this.visitLogRepository = visitLogRepository;
        this.clock = clock;
    }

    @Override
    @Async("observabilityTaskExecutor")
    public void recordVisit(String clientIp) {
        try {
            visitLogRepository.save(new VisitLog(LocalDateTime.now(clock), clientIp));
        } catch (RuntimeException e) {
            log.warn("Visit log could not be persisted", e);
        }
    }
}
