package com.example.monkey.shared.application.observability;

import com.example.monkey.shared.application.observability.dto.AuditTraceEventDto;
import com.example.monkey.shared.application.tenant.ActiveTenantIterator;
import com.example.monkey.shared.application.tenant.ActiveTenantIterator.IterationResult;
import com.example.monkey.shared.application.tenant.TenantContext;
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
    public static final String ORDER_PARTIALLY_SHIPPED = "ORDER_PARTIALLY_SHIPPED";
    public static final String ORDER_SHIPPED = "ORDER_SHIPPED";
    public static final String ORDER_PARTIALLY_RECEIVED = "ORDER_PARTIALLY_RECEIVED";
    public static final String ORDER_RECEIVED = "ORDER_RECEIVED";
    public static final String ORDER_SHIPMENT_CREATED = "ORDER_SHIPMENT_CREATED";
    public static final String ORDER_SHIPMENT_RECEIVED = "ORDER_SHIPMENT_RECEIVED";
    public static final String ORDER_RETURN_REQUESTED = "ORDER_RETURN_REQUESTED";
    public static final String ORDER_RETURN_APPROVED = "ORDER_RETURN_APPROVED";
    public static final String ORDER_RETURN_SHIPPED = "ORDER_RETURN_SHIPPED";
    public static final String ORDER_REFUNDED = "ORDER_REFUNDED";
    public static final String ORDER_REVIEWED = "ORDER_REVIEWED";
    public static final String ORDER_HIDDEN = "ORDER_HIDDEN";
    public static final String PRODUCT_SPU_CREATED = "PRODUCT_SPU_CREATED";
    public static final String PRODUCT_STATUS_CHANGED = "PRODUCT_STATUS_CHANGED";
    public static final String INVENTORY_RESERVED = "INVENTORY_RESERVED";
    public static final String INVENTORY_RELEASED = "INVENTORY_RELEASED";
    public static final String INVENTORY_DEDUCTED = "INVENTORY_DEDUCTED";
    public static final String INVENTORY_COMPENSATED = "INVENTORY_COMPENSATED";
    public static final String INVENTORY_RECONCILED = "INVENTORY_RECONCILED";
    public static final String MARKETING_COUPON_CLAIMED = "MARKETING_COUPON_CLAIMED";
    public static final String MARKETING_COUPON_REDEEMED = "MARKETING_COUPON_REDEEMED";
    public static final String MARKETING_COUPON_RETURNED = "MARKETING_COUPON_RETURNED";
    public static final String MARKETING_SECKILL_ORDERED = "MARKETING_SECKILL_ORDERED";
    public static final String MARKETING_SECKILL_DENIED = "MARKETING_SECKILL_DENIED";
    public static final String MARKETING_GROUP_JOINED = "MARKETING_GROUP_JOINED";
    public static final String MARKETING_GROUP_CANCELLED = "MARKETING_GROUP_CANCELLED";
    public static final String CART_ITEM_CHANGED = "CART_ITEM_CHANGED";
    public static final String CART_CHECKOUT_CREATED = "CART_CHECKOUT_CREATED";
    public static final String PAYMENT_CREATED = "PAYMENT_CREATED";
    public static final String PAYMENT_PAID = "PAYMENT_PAID";
    public static final String PAYMENT_FAILED = "PAYMENT_FAILED";
    public static final String PAYMENT_CALLBACK_ACCEPTED = "PAYMENT_CALLBACK_ACCEPTED";
    public static final String PAYMENT_REFUNDED = "PAYMENT_REFUNDED";
    public static final String PAYMENT_ADMIN_READ = "PAYMENT_ADMIN_READ";
    public static final String PAYMENT_ADMIN_REFUNDED = "PAYMENT_ADMIN_REFUNDED";
    public static final String PAYMENT_RECONCILED = "PAYMENT_RECONCILED";
    public static final String PAYMENT_HIGH_VALUE_DENIED = "PAYMENT_HIGH_VALUE_DENIED";
    public static final String LOGISTICS_SHIPMENT_CREATED = "LOGISTICS_SHIPMENT_CREATED";
    public static final String LOGISTICS_WEBHOOK_ACCEPTED = "LOGISTICS_WEBHOOK_ACCEPTED";
    public static final String LOGISTICS_SIGNED = "LOGISTICS_SIGNED";
    public static final String LOGISTICS_FREIGHT_QUOTED = "LOGISTICS_FREIGHT_QUOTED";
    public static final String LOGISTICS_ADDRESS_PARSED = "LOGISTICS_ADDRESS_PARSED";
    public static final String MEMBERSHIP_IDENTITY_VERIFIED = "MEMBERSHIP_IDENTITY_VERIFIED";
    public static final String MEMBERSHIP_CHECKED_IN = "MEMBERSHIP_CHECKED_IN";
    public static final String MEMBERSHIP_POINTS_EARNED = "MEMBERSHIP_POINTS_EARNED";
    public static final String MEMBERSHIP_POINTS_REDEEMED = "MEMBERSHIP_POINTS_REDEEMED";
    public static final String MEMBERSHIP_LEVEL_CHANGED = "MEMBERSHIP_LEVEL_CHANGED";
    public static final String MEMBERSHIP_LEVEL_DENIED = "MEMBERSHIP_LEVEL_DENIED";
    public static final String MEMBERSHIP_COLLECTION_ADDED = "MEMBERSHIP_COLLECTION_ADDED";
    public static final String MEMBERSHIP_COLLECTION_REMOVED = "MEMBERSHIP_COLLECTION_REMOVED";
    public static final String MEMBERSHIP_PRICE_DROP_NOTIFIED = "MEMBERSHIP_PRICE_DROP_NOTIFIED";
    public static final String SEARCH_QUERY_RECORDED = "SEARCH_QUERY_RECORDED";
    public static final String SEARCH_PROFILE_UPDATED = "SEARCH_PROFILE_UPDATED";
    public static final String SEARCH_CONVERSION_RECORDED = "SEARCH_CONVERSION_RECORDED";
    public static final String RISK_DECISION_RECORDED = "RISK_DECISION_RECORDED";
    public static final String RISK_REVIEW_DECIDED = "RISK_REVIEW_DECIDED";
    public static final String RISK_REVIEW_DENIED = "RISK_REVIEW_DENIED";
    public static final String TRACKING_EVENT_RECORDED = "TRACKING_EVENT_RECORDED";
    public static final String USER_PROFILE_TAG_UPDATED = "USER_PROFILE_TAG_UPDATED";
    public static final String PRODUCT_PROFILE_UPDATED = "PRODUCT_PROFILE_UPDATED";
    public static final String TENANT_CREATED = "TENANT_CREATED";
    public static final String TENANT_RENEWED = "TENANT_RENEWED";
    public static final String TENANT_DOWNGRADED = "TENANT_DOWNGRADED";
    public static final String TENANT_CONFIG_UPDATED = "TENANT_CONFIG_UPDATED";
    public static final String TENANT_BILL_GENERATED = "TENANT_BILL_GENERATED";
    public static final String TENANT_EXPORT_REQUESTED = "TENANT_EXPORT_REQUESTED";
    public static final String TENANT_EXPORT_COMPLETED = "TENANT_EXPORT_COMPLETED";

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private static final int DEFAULT_RETENTION_DAYS = 180;
    private static final Pattern SENSITIVE_DETAIL_PATTERN =
            Pattern.compile("(?i)(password|token|secret|authorization|cookie|phone|email|address)=[^,;\\s]+");

    private final AuditLogStore auditLogStore;
    private final ActiveTenantIterator activeTenantIterator;
    private final Clock clock;
    private final int retentionDays;

    @Autowired
    public AuditService(
            AuditLogStore auditLogStore,
            ActiveTenantIterator activeTenantIterator,
            @Value("${app.audit.retention-days:180}") int retentionDays) {
        this(auditLogStore, activeTenantIterator, Clock.systemUTC(), retentionDays);
    }

    public AuditService(AuditLogStore auditLogStore, int retentionDays) {
        this(auditLogStore, null, Clock.systemUTC(), retentionDays);
    }

    AuditService(AuditLogStore auditLogStore, Clock clock) {
        this(auditLogStore, null, clock, DEFAULT_RETENTION_DAYS);
    }

    AuditService(AuditLogStore auditLogStore, Clock clock, int retentionDays) {
        this(auditLogStore, null, clock, retentionDays);
    }

    AuditService(
            AuditLogStore auditLogStore, ActiveTenantIterator activeTenantIterator, Clock clock, int retentionDays) {
        this.auditLogStore = auditLogStore;
        this.activeTenantIterator = activeTenantIterator;
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
        persist(eventType, outcome, actorUserId, actorRole, subject, sourceIp, detail);
    }

    public void recordReliable(
            String eventType,
            String outcome,
            Long actorUserId,
            String actorRole,
            String subject,
            String sourceIp,
            String detail) {
        persist(eventType, outcome, actorUserId, actorRole, subject, sourceIp, detail);
    }

    private void persist(
            String eventType,
            String outcome,
            Long actorUserId,
            String actorRole,
            String subject,
            String sourceIp,
            String detail) {
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
    public IterationResult purgeExpiredAuditLogs() {
        LocalDateTime cutoff = LocalDateTime.now(clock).minusDays(retentionDays);
        IterationResult result;
        if (activeTenantIterator == null) {
            long deleted = purgeCurrentTenant(cutoff);
            result = new IterationResult(List.of(TenantContext.currentTenantIdOrDefault()), List.of(), deleted);
        } else {
            result = activeTenantIterator.forEachActiveTenant(tenantId -> purgeCurrentTenant(cutoff));
        }
        log.info(
                "Audit log retention completed: successfulTenants={}, failedTenants={}, deleted={}",
                result.successfulTenantIds().size(),
                result.failedTenantIds().size(),
                result.affectedRows());
        return result;
    }

    private long purgeCurrentTenant(LocalDateTime cutoff) {
        long deleted = auditLogStore.deleteCreatedBefore(cutoff);
        if (deleted > 0) {
            log.info(
                    "Deleted {} audit log records older than {} days for tenantId={}",
                    deleted,
                    retentionDays,
                    TenantContext.currentTenantIdOrDefault());
        }
        return deleted;
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
