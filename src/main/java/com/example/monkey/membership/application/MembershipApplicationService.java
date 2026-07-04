package com.example.monkey.membership.application;

import static com.example.monkey.shared.application.security.AuthenticatedPrincipals.requireUserId;

import com.example.monkey.membership.application.dto.BrowseRecordRequestDto;
import com.example.monkey.membership.application.dto.CheckInResponseDto;
import com.example.monkey.membership.application.dto.CollectionRequestDto;
import com.example.monkey.membership.application.dto.LevelChangeRequestDto;
import com.example.monkey.membership.application.dto.MemberCollectionDto;
import com.example.monkey.membership.application.dto.MembershipDashboardDto;
import com.example.monkey.membership.application.dto.PointsEarnRequestDto;
import com.example.monkey.membership.application.dto.PointsLedgerEntryDto;
import com.example.monkey.membership.application.dto.PointsRedeemRequestDto;
import com.example.monkey.membership.application.dto.PriceDropScanResponseDto;
import com.example.monkey.membership.application.dto.RealNameVerifyRequestDto;
import com.example.monkey.membership.domain.BrowseHistoryItem;
import com.example.monkey.membership.domain.MemberCollection;
import com.example.monkey.membership.domain.MemberProfile;
import com.example.monkey.membership.domain.MembershipActivityStore;
import com.example.monkey.membership.domain.MembershipCheckIn;
import com.example.monkey.membership.domain.MembershipLevel;
import com.example.monkey.membership.domain.MembershipLevelTransitionResolver;
import com.example.monkey.membership.domain.MembershipStore;
import com.example.monkey.membership.domain.PointsLedgerEntry;
import com.example.monkey.membership.domain.PointsLedgerType;
import com.example.monkey.membership.domain.PointsWallet;
import com.example.monkey.membership.domain.PriceDropEvent;
import com.example.monkey.membership.domain.ProductSnapshot;
import com.example.monkey.shared.application.observability.AuditService;
import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.shared.domain.id.IdGenerator;
import com.example.monkey.user.domain.UserAccountStore;
import com.example.monkey.user.domain.UserAccountStore.UserAccount;
import com.example.monkey.user.domain.UserMfaVerifier;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.regex.Pattern;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class MembershipApplicationService {

    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;
    private static final Pattern IDEMPOTENCY_KEY_PATTERN = Pattern.compile("[A-Za-z0-9._:-]+");
    private static final int BROWSE_HISTORY_LIMIT = 20;
    private static final int PRICE_DROP_BATCH_SIZE = 100;
    private static final String CUSTOMER_ROLE = "CUSTOMER";
    private static final String SYSTEM_ROLE = "SYSTEM";

    private final MembershipStore membershipStore;
    private final MembershipActivityStore activityStore;
    private final MembershipLevelTransitionResolver levelTransitionResolver;
    private final UserAccountStore userAccountStore;
    private final UserMfaVerifier userMfaVerifier;
    private final IdGenerator idGenerator;
    private final AuditService auditService;
    private final Clock clock;
    private final Duration browsingTtl;

    @Autowired
    public MembershipApplicationService(
            MembershipStore membershipStore,
            MembershipActivityStore activityStore,
            MembershipLevelTransitionResolver levelTransitionResolver,
            UserAccountStore userAccountStore,
            UserMfaVerifier userMfaVerifier,
            IdGenerator idGenerator,
            AuditService auditService,
            @Value("${app.membership.browsing-ttl:PT168H}") Duration browsingTtl) {
        this(
                membershipStore,
                activityStore,
                levelTransitionResolver,
                userAccountStore,
                userMfaVerifier,
                idGenerator,
                auditService,
                Clock.systemDefaultZone(),
                browsingTtl);
    }

    MembershipApplicationService(
            MembershipStore membershipStore,
            MembershipActivityStore activityStore,
            MembershipLevelTransitionResolver levelTransitionResolver,
            UserAccountStore userAccountStore,
            UserMfaVerifier userMfaVerifier,
            IdGenerator idGenerator,
            AuditService auditService,
            Clock clock,
            Duration browsingTtl) {
        this.membershipStore = membershipStore;
        this.activityStore = activityStore;
        this.levelTransitionResolver = levelTransitionResolver;
        this.userAccountStore = userAccountStore;
        this.userMfaVerifier = userMfaVerifier;
        this.idGenerator = idGenerator;
        this.auditService = auditService;
        this.clock = clock;
        this.browsingTtl = browsingTtl == null ? Duration.ofDays(7) : browsingTtl;
    }

    @WithSpan("membership.dashboard")
    @Transactional
    public MembershipDashboardDto dashboard(SessionUser currentUser) {
        Long userId = requireUserId(currentUser);
        return dashboardFor(userId, profile(userId), wallet(userId));
    }

    @WithSpan("membership.identity.verify")
    @Transactional
    public MembershipDashboardDto verifyIdentity(SessionUser currentUser, RealNameVerifyRequestDto request) {
        Long userId = requireUserId(currentUser);
        MemberProfile saved = membershipStore.saveProfile(profile(userId)
                .verifyIdentity(
                        requiredText(request.realName(), "realName"),
                        null,
                        requiredText(request.idCardNo(), "idCardNo"),
                        null,
                        now()));
        audit(AuditService.MEMBERSHIP_IDENTITY_VERIFIED, userId, "membership:" + userId, null, "verified=true");
        return dashboardFor(userId, saved, wallet(userId));
    }

    @WithSpan("membership.check-in")
    @Transactional
    public CheckInResponseDto checkIn(SessionUser currentUser, String idempotencyKey) {
        Long userId = requireUserId(currentUser);
        String key = normalizeIdempotencyKey(idempotencyKey);
        LocalDate today = LocalDate.now(clock);
        return membershipStore
                .findCheckInByIdempotencyKey(userId, key)
                .map(existing -> MembershipDtoAssembler.toCheckIn(existing, wallet(userId)))
                .orElseGet(() -> checkInLocked(userId, key, today));
    }

    @WithSpan("membership.points.earn")
    @Transactional
    public PointsLedgerEntryDto earnPoints(
            SessionUser currentUser, PointsEarnRequestDto request, String idempotencyKey) {
        Long userId = requireUserId(currentUser);
        String key = normalizeIdempotencyKey(idempotencyKey);
        long basePoints = request.amount().setScale(0, RoundingMode.DOWN).longValue();
        long points = Math.max(1, basePoints * profile(userId).level().pointsMultiplier());
        return applyPoints(userId, PointsLedgerType.PURCHASE, points, request.orderId(), request.referenceKey(), key);
    }

    @WithSpan("membership.points.redeem")
    @Transactional
    public PointsLedgerEntryDto redeemPoints(
            SessionUser currentUser, PointsRedeemRequestDto request, String idempotencyKey) {
        Long userId = requireUserId(currentUser);
        String key = normalizeIdempotencyKey(idempotencyKey);
        if (request.points() <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "points must be positive");
        }
        return applyPoints(userId, PointsLedgerType.REDEEM, -request.points(), null, request.referenceKey(), key);
    }

    @WithSpan("membership.level.change")
    @Transactional
    public MembershipDashboardDto changeLevel(SessionUser currentUser, LevelChangeRequestDto request) {
        Long userId = requireUserId(currentUser);
        UserAccount account = userAccountStore
                .findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "User account does not exist"));
        if (!account.mfaEnabled() || !userMfaVerifier.verifyCode(account.totpSecret(), request.totpCode())) {
            audit(AuditService.MEMBERSHIP_LEVEL_DENIED, userId, "membership:" + userId, null, "reason=totp");
            throw new BusinessException(ErrorCode.FORBIDDEN, "TOTP verification is required for level changes");
        }
        MemberProfile current = profile(userId);
        levelTransitionResolver.assertAllowed(current.level(), request.level());
        boolean updated = membershipStore.updateLevel(userId, current.version(), request.level(), now());
        if (!updated) {
            throw new BusinessException(ErrorCode.CONFLICT, "Membership profile changed concurrently");
        }
        membershipStore.saveLevelHistory(
                idGenerator.nextId(),
                userId,
                current.level(),
                request.level(),
                StringUtils.hasText(request.reason()) ? request.reason().trim() : "manual",
                userId,
                now());
        audit(
                AuditService.MEMBERSHIP_LEVEL_CHANGED,
                userId,
                "membership:" + userId,
                null,
                "from=" + current.level() + ",to=" + request.level());
        return dashboard(currentUser);
    }

    @WithSpan("membership.collection.add")
    @Transactional
    public MemberCollectionDto addCollection(SessionUser currentUser, CollectionRequestDto request) {
        Long userId = requireUserId(currentUser);
        ProductSnapshot product = product(request.productId());
        MemberCollection collection = membershipStore
                .findCollection(userId, product.id())
                .orElseGet(() -> new MemberCollection(
                        idGenerator.nextId(),
                        userId,
                        product.id(),
                        product.name(),
                        product.imageUrl(),
                        money(product.price()),
                        money(request.targetPrice()),
                        false,
                        0,
                        now(),
                        now()));
        MemberCollection saved = membershipStore.saveCollection(new MemberCollection(
                collection.id(),
                userId,
                product.id(),
                product.name(),
                product.imageUrl(),
                money(product.price()),
                money(request.targetPrice()),
                false,
                collection.version(),
                collection.createTime(),
                now()));
        audit(
                AuditService.MEMBERSHIP_COLLECTION_ADDED,
                userId,
                "product:" + product.id(),
                null,
                "targetPrice=" + saved.targetPrice());
        return MembershipDtoAssembler.toCollection(saved);
    }

    @WithSpan("membership.collection.delete")
    @Transactional
    public void removeCollection(SessionUser currentUser, Long productId) {
        Long userId = requireUserId(currentUser);
        membershipStore.deleteCollection(userId, productId);
        audit(AuditService.MEMBERSHIP_COLLECTION_REMOVED, userId, "product:" + productId, null, "removed=true");
    }

    @WithSpan("membership.browse.record")
    @Transactional
    public void recordBrowse(SessionUser currentUser, BrowseRecordRequestDto request) {
        Long userId = requireUserId(currentUser);
        ProductSnapshot product = product(request.productId());
        LocalDateTime viewedAt = now();
        activityStore.record(
                new BrowseHistoryItem(
                        idGenerator.nextId(),
                        userId,
                        product.id(),
                        product.name(),
                        product.imageUrl(),
                        viewedAt,
                        viewedAt.plus(browsingTtl)),
                browsingTtl);
    }

    @Scheduled(fixedDelayString = "${app.membership.price-drop-scan-delay:PT5M}")
    @SchedulerLock(
            name = "membership-price-drop-scan",
            lockAtMostFor = "${app.membership.price-drop-lock-at-most-for:PT10M}")
    @Transactional
    public PriceDropScanResponseDto scanPriceDrops() {
        int scanned = 0;
        int reminders = 0;
        for (MemberCollection collection : membershipStore.findCollectionsForPriceCheck(PRICE_DROP_BATCH_SIZE)) {
            scanned++;
            ProductSnapshot product =
                    membershipStore.findProduct(collection.productId()).orElse(null);
            if (product == null || !collection.priceDropped(product.price())) {
                continue;
            }
            membershipStore.savePriceDropEvent(new PriceDropEvent(
                    idGenerator.nextId(),
                    collection.id(),
                    collection.userId(),
                    collection.productId(),
                    collection.lastPrice(),
                    money(product.price()),
                    now()));
            membershipStore.saveCollection(collection.refreshPrice(money(product.price()), true, now()));
            audit(
                    AuditService.MEMBERSHIP_PRICE_DROP_NOTIFIED,
                    collection.userId(),
                    "product:" + collection.productId(),
                    null,
                    "newPrice=" + product.price());
            reminders++;
        }
        return MembershipDtoAssembler.toPriceDropScan(scanned, reminders);
    }

    private CheckInResponseDto checkInLocked(Long userId, String key, LocalDate today) {
        return membershipStore
                .findCheckIn(userId, today)
                .map(existing -> MembershipDtoAssembler.toCheckIn(existing, wallet(userId)))
                .orElseGet(() -> {
                    int streak = nextStreak(userId, today);
                    long reward = Math.min(50, 10L + Math.max(0, streak - 1) * 2L);
                    MembershipCheckIn saved = membershipStore.saveCheckIn(
                            new MembershipCheckIn(idGenerator.nextId(), userId, today, streak, reward, key, now()));
                    applyPoints(userId, PointsLedgerType.CHECK_IN, reward, null, "check-in:" + today, key);
                    audit(
                            AuditService.MEMBERSHIP_CHECKED_IN,
                            userId,
                            "membership:" + userId,
                            null,
                            "streak=" + saved.streakDays());
                    return MembershipDtoAssembler.toCheckIn(saved, wallet(userId));
                });
    }

    private PointsLedgerEntryDto applyPoints(
            Long userId, PointsLedgerType type, long points, Long orderId, String referenceKey, String idempotencyKey) {
        return membershipStore
                .findLedger(userId, idempotencyKey)
                .map(MembershipDtoAssembler::toLedger)
                .orElseGet(() -> {
                    PointsWallet current = wallet(userId);
                    PointsWallet next = current.apply(points, now());
                    if (!membershipStore.updateWallet(next)) {
                        throw new BusinessException(ErrorCode.CONFLICT, "Points wallet changed concurrently");
                    }
                    PointsLedgerEntry saved = membershipStore.saveLedger(new PointsLedgerEntry(
                            idGenerator.nextId(),
                            userId,
                            type,
                            points,
                            MembershipDtoAssembler.moneyEquivalent(Math.abs(points)),
                            orderId,
                            trim(referenceKey),
                            idempotencyKey,
                            now()));
                    if (points > 0) {
                        membershipStore.saveProfile(profile(userId).addGrowth(points, now()));
                    }
                    audit(
                            points >= 0
                                    ? AuditService.MEMBERSHIP_POINTS_EARNED
                                    : AuditService.MEMBERSHIP_POINTS_REDEEMED,
                            userId,
                            "points:" + saved.id(),
                            null,
                            "points=" + points + ",type=" + type);
                    return MembershipDtoAssembler.toLedger(saved);
                });
    }

    private int nextStreak(Long userId, LocalDate today) {
        return membershipStore
                .findLatestCheckInBefore(userId, today)
                .filter(previous -> previous.checkInDate().plusDays(1).equals(today))
                .map(previous -> previous.streakDays() + 1)
                .orElse(1);
    }

    private MembershipDashboardDto dashboardFor(Long userId, MemberProfile profile, PointsWallet wallet) {
        return MembershipDtoAssembler.toDashboard(
                profile,
                wallet,
                membershipStore.findCouponWallet(userId),
                membershipStore.findCollections(userId),
                activityStore.findRecent(userId, BROWSE_HISTORY_LIMIT));
    }

    private MemberProfile profile(Long userId) {
        return membershipStore
                .findProfile(userId)
                .orElseGet(() -> membershipStore.saveProfile(new MemberProfile(
                        idGenerator.nextId(),
                        userId,
                        MembershipLevel.BASIC,
                        0,
                        null,
                        null,
                        null,
                        null,
                        null,
                        0,
                        now(),
                        now())));
    }

    private PointsWallet wallet(Long userId) {
        return membershipStore
                .findWallet(userId)
                .orElseGet(() -> membershipStore.saveWallet(
                        new PointsWallet(idGenerator.nextId(), userId, 0, 0, 0, 0, now(), now())));
    }

    private ProductSnapshot product(Long productId) {
        return membershipStore
                .findProduct(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Product does not exist"));
    }

    private void audit(String eventType, Long actorUserId, String subject, String sourceIp, String detail) {
        auditService.record(
                eventType,
                AuditService.OUTCOME_SUCCESS,
                actorUserId,
                actorUserId == null ? SYSTEM_ROLE : CUSTOMER_ROLE,
                subject,
                sourceIp,
                detail);
    }

    private static String normalizeIdempotencyKey(String idempotencyKey) {
        if (!StringUtils.hasText(idempotencyKey)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Idempotency-Key header is required");
        }
        String normalized = idempotencyKey.trim();
        if (normalized.length() > MAX_IDEMPOTENCY_KEY_LENGTH
                || !IDEMPOTENCY_KEY_PATTERN.matcher(normalized).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Idempotency-Key header is invalid");
        }
        return normalized;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    private static String requiredText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, field + " is required");
        }
        return value.trim();
    }

    private static String trim(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
