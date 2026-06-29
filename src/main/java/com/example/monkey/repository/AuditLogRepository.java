package com.example.monkey.repository;

import com.example.monkey.entity.AuditLog;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    long deleteByCreatedAtBefore(LocalDateTime cutoff);

    List<AuditLog> findTop50ByTraceIdOrderByCreatedAtAsc(String traceId);
}
