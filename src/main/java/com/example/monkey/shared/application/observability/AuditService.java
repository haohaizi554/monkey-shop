package com.example.monkey.shared.application.observability;

import com.example.monkey.shared.application.observability.dto.AuditTraceEventDto;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.shared.domain.observability.AuditLogStore;
import com.example.monkey.shared.domain.observability.AuditLogStore.AuditEventRecord;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class AuditService {

    public static final String OUTCOME_SUCCESS = "SUCCESS";
    public static final String OUTCOME_FAILURE = "FAILURE";
    public static final String OUTCOME_DENIED = "DENIED";
    public static final String OUTCOME_ACCEPTED = "ACCEPTED";

    public static final String LOGIN_SUCCESS = "LOGIN_SUCCESS";
    public static final String LOGIN_FAILURE = "LOGIN_FAILURE";
    public static final String LOGIN_RATE_LIMITED = "LOGIN_RATE_LIMITED";
    public static final String LOGOUT_SUCCESS = "LOGOUT_SUCCESS";
    public static final String LOGOUT_FAILURE = "LOGOUT_FAILURE";
    public static final String ADMIN_MFA_FAILURE = "ADMIN_MFA_FAILURE";
    public static final String PASSWORD_RESET_REQUEST = "PASSWORD_RESET_REQUEST";
    public static final String PASSWORD_RESET_SUCCESS = "PASSWORD_RESET_SUCCESS";
    public static final String PASSWORD_RESET_FAILURE = "PASSWORD_RESET_FAILURE";
    public static final String PASSWORD_CHANGE_SUCCESS = "PASSWORD_CHANGE_SUCCESS";
    public static final String PASSWORD_CHANGE_FAILURE = "PASSWORD_CHANGE_FAILURE";
    public static final String REFRESH_TOKEN_SUCCESS = "REFRESH_TOKEN_SUCCESS";
    public static final String REFRESH_TOKEN_FAILURE = "REFRESH_TOKEN_FAILURE";
    public static final String REFRESH_TOKEN_REPLAY = "REFRESH_TOKEN_REPLAY";
    public static final String ORDER_CREATED = "ORDER_CREATED";
    public static final String ORDER_CREATE_FAILURE = "ORDER_CREATE_FAILURE";
    public static final String ORDER_SHIPPED = "ORDER_SHIPPED";
    public static final String ORDER_RECEIVED = "ORDER_RECEIVED";
    public static final String ORDER_RETURN_REQUESTED = "ORDER_RETURN_REQUESTED";
    public static final String ORDER_RETURN_APPROVED = "ORDER_RETURN_APPROVED";
    public static final String ORDER_RETURN_SHIPPED = "ORDER_RETURN_SHIPPED";
    public static final String ORDER_REFUNDED = "ORDER_REFUNDED";
    public static final String ORDER_HIDDEN = "ORDER_HIDDEN";
    public static final String PRODUCT_SPU_CREATED = "PRODUCT_SPU_CREATED";
    public static final String PRODUCT_STATUS_CHANGED = "PRODUCT_STATUS_CHANGED";
    public static final String INVENTORY_RESERVED = "INVENTORY_RESERVED";
    public static final String INVENTORY_RELEASED = "INVENTORY_RELEASED";
    public static final String INVENTORY_DEDUCTED = "INVENTORY_DEDUCTED";
    public static final String INVENTORY_COMPENSATED = "INVENTORY_COMPENSATED";
    public static final String INVENTORY_RECONCILED = "INVENTORY_RECONCILED";

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private static final int DEFAULT_RETENTION_DAYS = 180;
    private static final Pattern SENSITIVE_DETAIL_PATTERN =
            Pattern.compile("(?i)(password|token|secret|authorization|cookie|phone|email|address)=[^,;\\s]+");

    private final AuditLogStore auditLogStore;
    private final Clock clock;
    private final int retentionDays;

    @Autowired
    public AuditService(AuditLogStore auditLogStore, @Value("${app.audit.retention-days:180}") int retentionDays) {
        this(auditLogStore, Clock.systemUTC(), retentionDays);
    }

    AuditService(AuditLogStore auditLogStore, Clock clock) {
        this(auditLogStore, clock, DEFAULT_RETENTION_DAYS);
    }

    AuditService(AuditLogStore auditLogStore, Clock clock, int retentionDays) {
        this.auditLogStore = auditLogStore;
        this.clock = clock;
        this.retentionDays = retentionDays;
    }

    @Async("observabilityTaskExecutor")
    public void record(
            String eventType,
            String outcome,
            Long actorUserId,
            String actorRole,
            String subject,
            String sourceIp,
            String detail) {
        try {
            AuditEventRecord auditEvent = new AuditEventRecord(
                    null,
                    limit(eventType, 64),
                    limit(outcome, 32),
                    actorUserId,
                    limit(actorRole, 32),
                    hashSubject(subject),
                    limit(sourceIp, 64),
                    limit(TraceIds.currentOrCreate(), 128),
                    limit(sanitizeDetail(detail), 255),
                    LocalDateTime.now(clock));
            auditLogStore.save(auditEvent);
        } catch (RuntimeException e) {
            log.warn("Audit event {} could not be persisted", eventType, e);
        }
    }

    @Transactional(readOnly = true)
    public List<AuditTraceEventDto> findByTraceId(String traceId) {
        String normalizedTraceId = normalizeTraceId(traceId);
        return auditLogStore.findFirst50ByTraceId(normalizedTraceId).stream()
                .map(AuditService::toDto)
                .toList();
    }

    @Scheduled(cron = "${app.audit.retention-cron:0 20 3 * * *}")
    @SchedulerLock(name = "audit-log-retention", lockAtMostFor = "${app.audit.retention-lock-at-most-for:PT30M}")
    @Transactional
    public void purgeExpiredAuditLogs() {
        LocalDateTime cutoff = LocalDateTime.now(clock).minusDays(retentionDays);
        long deleted = auditLogStore.deleteCreatedBefore(cutoff);
        if (deleted > 0) {
            log.info("Deleted {} audit log records older than {} days", deleted, retentionDays);
        }
    }

    private static String hashSubject(String subject) {
        if (!StringUtils.hasText(subject)) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(subject.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required for audit subject hashing", e);
        }
    }

    private static String limit(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private static String sanitizeDetail(String detail) {
        if (!StringUtils.hasText(detail)) {
            return null;
        }
        return SENSITIVE_DETAIL_PATTERN.matcher(detail).replaceAll("$1=****");
    }

    private static String normalizeTraceId(String traceId) {
        if (!StringUtils.hasText(traceId)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "traceId is required");
        }
        String normalizedTraceId = traceId.trim();
        if (normalizedTraceId.length() > 128) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "traceId is too long");
        }
        return normalizedTraceId;
    }

    private static AuditTraceEventDto toDto(AuditEventRecord auditLog) {
        return new AuditTraceEventDto(
                auditLog.id(),
                auditLog.eventType(),
                auditLog.outcome(),
                auditLog.actorUserId(),
                auditLog.actorRole(),
                auditLog.subjectHash(),
                auditLog.sourceIp(),
                auditLog.traceId(),
                auditLog.detail(),
                auditLog.createdAt());
    }
}
