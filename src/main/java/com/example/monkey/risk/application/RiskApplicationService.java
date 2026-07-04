package com.example.monkey.risk.application;

import static com.example.monkey.shared.application.security.AuthenticatedPrincipals.requireUserId;

import com.example.monkey.order.application.observability.BusinessMetricsService;
import com.example.monkey.risk.application.dto.RiskAssessmentRequestDto;
import com.example.monkey.risk.application.dto.RiskAssessmentResponseDto;
import com.example.monkey.risk.application.dto.RiskReviewCaseDto;
import com.example.monkey.risk.application.dto.RiskReviewResolveRequestDto;
import com.example.monkey.risk.domain.RiskAssessment;
import com.example.monkey.risk.domain.RiskBlindIndexService;
import com.example.monkey.risk.domain.RiskCache;
import com.example.monkey.risk.domain.RiskDecision;
import com.example.monkey.risk.domain.RiskDeviceFingerprint;
import com.example.monkey.risk.domain.RiskPolicy;
import com.example.monkey.risk.domain.RiskReviewCase;
import com.example.monkey.risk.domain.RiskReviewStatus;
import com.example.monkey.risk.domain.RiskScore;
import com.example.monkey.risk.domain.RiskSignal;
import com.example.monkey.risk.domain.RiskSignalType;
import com.example.monkey.risk.domain.RiskStore;
import com.example.monkey.shared.application.observability.AuditService;
import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.shared.domain.id.IdGenerator;
import com.example.monkey.user.domain.SessionTokenService;
import com.example.monkey.user.domain.UserAccountStore;
import com.example.monkey.user.domain.UserAccountStore.UserAccount;
import com.example.monkey.user.domain.UserMfaVerifier;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class RiskApplicationService {

    private static final String CUSTOMER_ROLE = "CUSTOMER";
    private static final String ADMIN_ROLE = "ADMIN";
    private static final int REVIEW_LIMIT = 100;

    private final RiskStore riskStore;
    private final RiskCache riskCache;
    private final RiskBlindIndexService riskBlindIndexService;
    private final UserAccountStore userAccountStore;
    private final UserMfaVerifier userMfaVerifier;
    private final SessionTokenService sessionTokenService;
    private final IdGenerator idGenerator;
    private final AuditService auditService;
    private final BusinessMetricsService businessMetricsService;
    private final Clock clock;
    private final Duration deviceTtl;
    private final Duration seckillTtl;
    private final Duration scoreTtl;

    @Autowired
    public RiskApplicationService(
            RiskStore riskStore,
            RiskCache riskCache,
            RiskBlindIndexService riskBlindIndexService,
            UserAccountStore userAccountStore,
            UserMfaVerifier userMfaVerifier,
            SessionTokenService sessionTokenService,
            IdGenerator idGenerator,
            AuditService auditService,
            BusinessMetricsService businessMetricsService,
            @Value("${app.risk.device-ttl:PT720H}") Duration deviceTtl,
            @Value("${app.risk.seckill-window:PT5M}") Duration seckillTtl,
            @Value("${app.risk.score-ttl:PT30M}") Duration scoreTtl) {
        this(
                riskStore,
                riskCache,
                riskBlindIndexService,
                userAccountStore,
                userMfaVerifier,
                sessionTokenService,
                idGenerator,
                auditService,
                businessMetricsService,
                Clock.systemDefaultZone(),
                deviceTtl,
                seckillTtl,
                scoreTtl);
    }

    RiskApplicationService(
            RiskStore riskStore,
            RiskCache riskCache,
            RiskBlindIndexService riskBlindIndexService,
            UserAccountStore userAccountStore,
            UserMfaVerifier userMfaVerifier,
            SessionTokenService sessionTokenService,
            IdGenerator idGenerator,
            AuditService auditService,
            BusinessMetricsService businessMetricsService,
            Clock clock,
            Duration deviceTtl,
            Duration seckillTtl,
            Duration scoreTtl) {
        this.riskStore = riskStore;
        this.riskCache = riskCache;
        this.riskBlindIndexService = riskBlindIndexService;
        this.userAccountStore = userAccountStore;
        this.userMfaVerifier = userMfaVerifier;
        this.sessionTokenService = sessionTokenService;
        this.idGenerator = idGenerator;
        this.auditService = auditService;
        this.businessMetricsService = businessMetricsService;
        this.clock = clock;
        this.deviceTtl = positive(deviceTtl, Duration.ofDays(30));
        this.seckillTtl = positive(seckillTtl, Duration.ofMinutes(5));
        this.scoreTtl = positive(scoreTtl, Duration.ofMinutes(30));
    }

    @WithSpan("risk.assess")
    @Transactional
    public RiskAssessmentResponseDto assess(
            SessionUser currentUser, RiskAssessmentRequestDto request, String requestClientIp) {
        Long userId = requireUserId(currentUser);
        RiskAssessmentRequestDto safeRequest = request == null
                ? new RiskAssessmentRequestDto(null, null, null, null, null, null, null, null, null, null)
                : request;
        LocalDateTime now = now();
        String clientIp = StringUtils.hasText(safeRequest.clientIp()) ? safeRequest.clientIp() : requestClientIp;
        String phoneHmac = phoneHmac(safeRequest, userId);
        String deviceHash = deviceHash(safeRequest.deviceFingerprint(), clientIp, userId);
        riskStore.saveDeviceFingerprint(new RiskDeviceFingerprint(
                idGenerator.nextId(), userId, deviceHash, clientIp, phoneHmac, now, now, now.plus(deviceTtl)));
        riskCache.rememberDeviceFingerprint(deviceHash, userId, phoneHmac, deviceTtl);

        long usersOnDevice = Math.max(
                riskCache.countUsersForDevice(deviceHash),
                riskStore.countDistinctUsersByDevice(deviceHash, now.minus(deviceTtl)));
        long phonesOnDevice = Math.max(
                riskCache.countPhonesForDevice(deviceHash),
                riskStore.countDistinctPhonesByDevice(deviceHash, now.minus(deviceTtl)));
        long seckillUsers = recordSeckillAttempt(safeRequest, deviceHash, userId);
        boolean totpVerified = verifyTotpIfPresent(userId, safeRequest.totpCode());
        RiskAssessment assessment = RiskPolicy.assess(new RiskPolicy.RiskPolicyInput(
                usersOnDevice,
                phonesOnDevice,
                seckillUsers,
                isSelfBuy(userId, safeRequest.sellerUserId()),
                safeRequest.priceBefore(),
                safeRequest.priceAfter(),
                totpVerified));
        boolean productAutoUnlisted = maybeUnlistAnomalousProduct(safeRequest, assessment);
        boolean userTokensRevoked = maybeRevokeTokens(userId, assessment);
        RiskScore score = riskStore.saveRiskScore(new RiskScore(
                idGenerator.nextId(),
                userId,
                deviceHash,
                phoneHmac,
                assessment.score(),
                assessment.decision(),
                assessment.signals(),
                now,
                now.plus(scoreTtl),
                0L));
        riskCache.cacheScore(score, scoreTtl);
        businessMetricsService.recordRiskDecision(score.score(), productAutoUnlisted, userTokensRevoked);
        Long reviewCaseId = maybeEnqueueReview(score, safeRequest, productAutoUnlisted);
        auditDecision(currentUser, score, clientIp, productAutoUnlisted, userTokensRevoked);
        return RiskDtoAssembler.toAssessment(score, reviewCaseId, productAutoUnlisted, userTokensRevoked);
    }

    @WithSpan("risk.review.list")
    @Transactional(readOnly = true)
    public List<RiskReviewCaseDto> reviewQueue() {
        return riskStore.findOpenReviewCases(REVIEW_LIMIT).stream()
                .map(RiskDtoAssembler::toReviewCase)
                .toList();
    }

    @WithSpan("risk.review.resolve")
    @Transactional
    public RiskReviewCaseDto resolveReview(
            SessionUser currentUser, Long caseId, RiskReviewResolveRequestDto request, String clientIp) {
        Long adminId = requireUserId(currentUser);
        RiskReviewResolveRequestDto safeRequest =
                request == null ? new RiskReviewResolveRequestDto(RiskReviewStatus.APPROVED, null, null) : request;
        if (safeRequest.status() == RiskReviewStatus.PENDING) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "review status must be final");
        }
        if (safeRequest.status() == RiskReviewStatus.BLOCKED) {
            requireAdminTotp(adminId, safeRequest.totpCode());
        }
        RiskReviewCase current = riskStore
                .findReviewCase(caseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Risk review case does not exist"));
        if (current.status() != RiskReviewStatus.PENDING) {
            throw new BusinessException(ErrorCode.CONFLICT, "Risk review case was already handled");
        }
        if (safeRequest.status() == RiskReviewStatus.BLOCKED) {
            sessionTokenService.revokeUserTokens(current.userId());
            businessMetricsService.recordRiskBlocked();
        }
        RiskReviewCase resolved = riskStore.saveReviewCase(new RiskReviewCase(
                current.id(),
                current.userId(),
                current.orderId(),
                current.productId(),
                current.type(),
                current.score(),
                safeRequest.status(),
                current.detail(),
                current.createdAt(),
                now(),
                adminId,
                normalizeResolution(safeRequest.resolution())));
        auditService.record(
                AuditService.RISK_REVIEW_DECIDED,
                AuditService.OUTCOME_SUCCESS,
                adminId,
                ADMIN_ROLE,
                "risk-review:" + resolved.id(),
                clientIp,
                "status=" + resolved.status());
        return RiskDtoAssembler.toReviewCase(resolved);
    }

    private long recordSeckillAttempt(RiskAssessmentRequestDto request, String deviceHash, Long userId) {
        if (request.seckillActivityId() == null || request.productId() == null) {
            return 0L;
        }
        return riskCache.recordSeckillAttempt(
                request.seckillActivityId(), request.productId(), deviceHash, userId, seckillTtl);
    }

    private boolean maybeUnlistAnomalousProduct(RiskAssessmentRequestDto request, RiskAssessment assessment) {
        boolean priceAnomaly =
                assessment.signals().stream().anyMatch(signal -> signal.type() == RiskSignalType.PRICE_ANOMALY);
        if (!priceAnomaly || request.productId() == null) {
            return false;
        }
        boolean unlisted = riskStore.unlistProductForPriceAnomaly(request.productId());
        return unlisted;
    }

    private boolean maybeRevokeTokens(Long userId, RiskAssessment assessment) {
        if (assessment.decision() != RiskDecision.BLOCK) {
            return false;
        }
        sessionTokenService.revokeUserTokens(userId);
        return true;
    }

    private Long maybeEnqueueReview(RiskScore score, RiskAssessmentRequestDto request, boolean productAutoUnlisted) {
        if (score.decision() == RiskDecision.ALLOW) {
            return null;
        }
        RiskSignal primarySignal = primarySignal(score.signals());
        RiskReviewCase reviewCase = riskStore.enqueueReview(new RiskReviewCase(
                idGenerator.nextId(),
                score.userId(),
                request.orderId(),
                request.productId(),
                primarySignal.type(),
                score.score(),
                RiskReviewStatus.PENDING,
                primarySignal.detail() + ",decision=" + score.decision() + ",autoUnlisted=" + productAutoUnlisted,
                score.assessedAt(),
                null,
                null,
                null));
        return reviewCase.id();
    }

    private void auditDecision(
            SessionUser currentUser,
            RiskScore score,
            String clientIp,
            boolean productAutoUnlisted,
            boolean userTokensRevoked) {
        auditService.record(
                AuditService.RISK_DECISION_RECORDED,
                score.decision() == RiskDecision.ALLOW ? AuditService.OUTCOME_SUCCESS : AuditService.OUTCOME_DENIED,
                score.userId(),
                currentUser == null ? CUSTOMER_ROLE : currentUser.role(),
                "risk-score:" + score.userId(),
                clientIp,
                "score=" + score.score()
                        + ",decision=" + score.decision()
                        + ",autoUnlisted=" + productAutoUnlisted
                        + ",tokensRevoked=" + userTokensRevoked);
    }

    private boolean verifyTotpIfPresent(Long userId, String totpCode) {
        if (!StringUtils.hasText(totpCode)) {
            return false;
        }
        UserAccount account = userAccountStore
                .findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "User account does not exist"));
        return account.mfaEnabled() && userMfaVerifier.verifyCode(account.totpSecret(), totpCode);
    }

    private void requireAdminTotp(Long adminId, String totpCode) {
        UserAccount account = userAccountStore
                .findById(adminId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "User account does not exist"));
        if (!account.mfaEnabled() || !userMfaVerifier.verifyCode(account.totpSecret(), totpCode)) {
            auditService.record(
                    AuditService.RISK_REVIEW_DENIED,
                    AuditService.OUTCOME_DENIED,
                    adminId,
                    ADMIN_ROLE,
                    "risk-review",
                    null,
                    "reason=totp");
            throw new BusinessException(ErrorCode.FORBIDDEN, "TOTP verification is required for blocking risk cases");
        }
    }

    private String phoneHmac(RiskAssessmentRequestDto request, Long userId) {
        if (StringUtils.hasText(request.phone())) {
            return phoneBlindIndex(request.phone());
        }
        return userAccountStore
                .findById(userId)
                .map(UserAccount::phone)
                .filter(StringUtils::hasText)
                .map(this::phoneBlindIndex)
                .orElse(null);
    }

    private String deviceHash(String deviceFingerprint, String clientIp, Long userId) {
        String raw = StringUtils.hasText(deviceFingerprint)
                ? deviceFingerprint
                : "ip:" + (StringUtils.hasText(clientIp) ? clientIp : "unknown") + ":user:" + userId;
        return blindIndex(raw);
    }

    private String blindIndex(String value) {
        return riskBlindIndexService.blindIndex(value);
    }

    private String phoneBlindIndex(String value) {
        return riskBlindIndexService.phoneBlindIndex(value);
    }

    private static boolean isSelfBuy(Long userId, Long sellerUserId) {
        return userId != null && userId.equals(sellerUserId);
    }

    private static RiskSignal primarySignal(List<RiskSignal> signals) {
        return signals.stream()
                .filter(signal -> signal.weight() > 0)
                .findFirst()
                .orElse(new RiskSignal(RiskSignalType.HIGH_RISK_SCORE, 0, "manual review"));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private static String normalizeResolution(String resolution) {
        return StringUtils.hasText(resolution) ? resolution.trim() : null;
    }

    private static Duration positive(Duration duration, Duration fallback) {
        return duration == null || duration.isZero() || duration.isNegative() ? fallback : duration;
    }
}
