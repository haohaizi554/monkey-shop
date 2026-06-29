package com.example.monkey.shared.infrastructure.observability;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    long deleteByCreatedAtBefore(LocalDateTime cutoff);

    List<AuditLog> findTop50ByTraceIdOrderByCreatedAtAsc(String traceId);
}
