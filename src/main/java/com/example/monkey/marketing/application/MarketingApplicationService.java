package com.example.monkey.marketing.application;

import com.example.monkey.marketing.application.dto.CouponClaimRequestDto;
import com.example.monkey.marketing.application.dto.CouponRedeemRequestDto;
import com.example.monkey.marketing.application.dto.CouponResponseDto;
import com.example.monkey.marketing.application.dto.CouponReturnRequestDto;
import com.example.monkey.marketing.application.dto.GroupBuyJoinRequestDto;
import com.example.monkey.marketing.application.dto.GroupBuyTeamResponseDto;
import com.example.monkey.marketing.application.dto.MarketingPriceQuoteDto;
import com.example.monkey.marketing.application.dto.MarketingPriceRequestDto;
import com.example.monkey.marketing.application.dto.SeckillOrderResponseDto;
import com.example.monkey.marketing.application.dto.SeckillRequestDto;
import com.example.monkey.marketing.domain.CouponDefinition;
import com.example.monkey.marketing.domain.CouponType;
import com.example.monkey.marketing.domain.GroupBuyActivity;
import com.example.monkey.marketing.domain.GroupBuyStatus;
import com.example.monkey.marketing.domain.GroupBuyTeam;
import com.example.monkey.marketing.domain.MarketingIdempotencyStore;
import com.example.monkey.marketing.domain.MarketingLockManager;
import com.example.monkey.marketing.domain.MarketingPriceQuote;
import com.example.monkey.marketing.domain.MarketingStore;
import com.example.monkey.marketing.domain.SeckillActivity;
import com.example.monkey.marketing.domain.SeckillOrder;
import com.example.monkey.marketing.domain.UserCoupon;
import com.example.monkey.shared.application.observability.AuditService;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.shared.domain.id.IdGenerator;
import com.example.monkey.user.application.CaptchaService;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class MarketingApplicationService {

    private static final Duration IDEMPOTENCY_TTL = Duration.ofMinutes(30);
    private static final int EXPIRY_BATCH_SIZE = 100;
    private static final String TURNSTILE_ACTION_SECKILL = "seckill";
    private static final String SYSTEM_ACTOR = "SYSTEM";

    private final MarketingStore marketingStore;
    private final MarketingIdempotencyStore idempotencyStore;
    private final MarketingLockManager lockManager;
    private final IdGenerator idGenerator;
    private final AuditService auditService;
    private final CaptchaService captchaService;
    private final Clock clock;

    @Autowired
    public MarketingApplicationService(
            MarketingStore marketingStore,
            MarketingIdempotencyStore idempotencyStore,
            MarketingLockManager lockManager,
            IdGenerator idGenerator,
            AuditService auditService,
            ObjectProvider<CaptchaService> captchaServiceProvider,
            @Value("${app.marketing.clock:system}") String ignoredClockProperty) {
        this(
                marketingStore,
                idempotencyStore,
                lockManager,
                idGenerator,
                auditService,
                captchaServiceProvider.getIfAvailable(),
                Clock.systemDefaultZone());
    }

    public MarketingApplicationService(
            MarketingStore marketingStore,
            MarketingIdempotencyStore idempotencyStore,
            MarketingLockManager lockManager,
            IdGenerator idGenerator,
            AuditService auditService,
            CaptchaService captchaService,
            Clock clock) {
        this.marketingStore = marketingStore;
        this.idempotencyStore = idempotencyStore;
        this.lockManager = lockManager;
        this.idGenerator = idGenerator;
        this.auditService = auditService;
        this.captchaService = captchaService;
        this.clock = clock;
    }

    @WithSpan("marketing.coupon.claim")
    @Transactional
    public CouponResponseDto claimCoupon(CouponClaimRequestDto request) {
        String key = normalizeKey(request.idempotencyKey(), "idempotency key");
        return lockManager.withCouponLock(request.couponId(), () -> claimCouponLocked(request, key));
    }

    @WithSpan("marketing.coupon.redeem")
    @Transactional
    public CouponResponseDto redeemCoupon(CouponRedeemRequestDto request) {
        UserCoupon coupon = requireUserCoupon(request.couponCode());
        UserCoupon saved = marketingStore.saveUserCoupon(coupon.redeem(request.orderId(), now()));
        audit(AuditService.MARKETING_COUPON_REDEEMED, saved.userId(), saved.couponCode(), "orderId=" + saved.orderId());
        return MarketingDtoAssembler.toResponse(saved);
    }

    @WithSpan("marketing.coupon.return")
    @Transactional
    public CouponResponseDto returnCoupon(CouponReturnRequestDto request) {
        UserCoupon coupon = requireUserCoupon(request.couponCode());
        UserCoupon saved = marketingStore.saveUserCoupon(coupon.returnToWallet(request.orderId()));
        audit(
                AuditService.MARKETING_COUPON_RETURNED,
                saved.userId(),
                saved.couponCode(),
                "orderId=" + request.orderId());
        return MarketingDtoAssembler.toResponse(saved);
    }

    @WithSpan("marketing.price.quote")
    @Transactional(readOnly = true)
    public MarketingPriceQuoteDto quotePrice(MarketingPriceRequestDto request) {
        return quotePrice(request, coupon -> coupon.matches(request.categoryId(), request.shopId()));
    }

    @WithSpan("marketing.price.quote.platform")
    @Transactional(readOnly = true)
    public MarketingPriceQuoteDto quotePlatformPrice(MarketingPriceRequestDto request) {
        return quotePrice(request, MarketingApplicationService::isPlatformCoupon);
    }

    @WithSpan("marketing.price.quote.store")
    @Transactional(readOnly = true)
    public MarketingPriceQuoteDto quoteStorePrice(MarketingPriceRequestDto request) {
        return quotePrice(
                request, coupon -> !isPlatformCoupon(coupon) && coupon.matches(request.categoryId(), request.shopId()));
    }

    private MarketingPriceQuoteDto quotePrice(MarketingPriceRequestDto request, Predicate<CouponDefinition> eligible) {
        BigDecimal orderAmount = request.orderAmount();
        Map<String, CouponDefinition> selected = new LinkedHashMap<>();
        for (String code : request.couponCodes() == null ? List.<String>of() : request.couponCodes()) {
            CouponDefinition coupon = marketingStore
                    .findCouponByCode(code)
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Coupon does not exist"));
            if (eligible.test(coupon)) {
                CouponDefinition previous = selected.get(coupon.stackGroup());
                if (previous == null
                        || coupon.discountFor(orderAmount).compareTo(previous.discountFor(orderAmount)) > 0) {
                    selected.put(coupon.stackGroup(), coupon);
                }
            }
        }
        BigDecimal discount = selected.values().stream()
                .sorted(Comparator.comparing(CouponDefinition::stackGroup))
                .map(coupon -> coupon.discountFor(orderAmount))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal payable = orderAmount.subtract(discount).max(BigDecimal.ZERO);
        return MarketingDtoAssembler.toResponse(new MarketingPriceQuote(
                orderAmount,
                orderAmount.subtract(payable),
                payable,
                selected.values().stream().map(CouponDefinition::code).toList()));
    }

    private static boolean isPlatformCoupon(CouponDefinition coupon) {
        return CouponType.THRESHOLD.equals(coupon.type()) || CouponType.PERCENT.equals(coupon.type());
    }

    @WithSpan("marketing.seckill.order")
    @Transactional
    public SeckillOrderResponseDto createSeckillOrder(SeckillRequestDto request, String clientIp) {
        validateHuman(request.turnstileToken(), clientIp);
        String key = normalizeKey(request.idempotencyKey(), "idempotency key");
        return lockManager.withSeckillLock(request.activityId(), () -> createSeckillOrderLocked(request, key));
    }

    @WithSpan("marketing.group-buy.join")
    @Transactional
    public GroupBuyTeamResponseDto joinGroupBuy(GroupBuyJoinRequestDto request) {
        Long lockKey = request.teamId() == null ? request.activityId() : request.teamId();
        String key = normalizeKey(request.idempotencyKey(), "idempotency key");
        return lockManager.withGroupBuyLock(lockKey, () -> joinGroupBuyLocked(request, key));
    }

    @Scheduled(fixedDelayString = "${app.marketing.group-buy-expire-delay:PT1M}")
    @SchedulerLock(
            name = "marketing-expire-group-buy-teams",
            lockAtMostFor = "${app.marketing.group-buy-expire-lock-at-most-for:PT10M}")
    @Transactional
    public void expireGroupBuyTeamsScheduled() {
        expireGroupBuyTeams();
    }

    @Transactional
    public int expireGroupBuyTeams() {
        int expired = 0;
        LocalDateTime now = now();
        for (GroupBuyTeam team : marketingStore.findExpiredOpenTeams(now, EXPIRY_BATCH_SIZE)) {
            GroupBuyTeam cancelled = team.cancelIfExpired(now);
            if (GroupBuyStatus.CANCELLED.equals(cancelled.status())) {
                marketingStore.saveGroupBuyTeam(cancelled);
                auditService.record(
                        AuditService.MARKETING_GROUP_CANCELLED,
                        AuditService.OUTCOME_SUCCESS,
                        null,
                        SYSTEM_ACTOR,
                        "group-buy:" + cancelled.id(),
                        null,
                        "activityId=" + cancelled.activityId());
                expired++;
            }
        }
        return expired;
    }

    private CouponResponseDto claimCouponLocked(CouponClaimRequestDto request, String key) {
        Optional<UserCoupon> existing = marketingStore.findUserCoupon(request.userId(), request.couponId());
        if (existing.isPresent()) {
            return MarketingDtoAssembler.toResponse(existing.get());
        }
        idempotencyStore.reserve("coupon:" + request.couponId(), request.userId(), key, "claim", IDEMPOTENCY_TTL);
        CouponDefinition coupon = marketingStore
                .findCoupon(request.couponId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Coupon does not exist"));
        CouponDefinition savedCoupon = marketingStore.saveCoupon(coupon.reserveClaim(now()));
        UserCoupon userCoupon = new UserCoupon(
                idGenerator.nextId(),
                savedCoupon.id(),
                savedCoupon.code(),
                request.userId(),
                com.example.monkey.marketing.domain.CouponStatus.CLAIMED,
                null,
                "coupon:" + key,
                now(),
                null);
        UserCoupon saved = marketingStore.saveUserCoupon(userCoupon);
        audit(
                AuditService.MARKETING_COUPON_CLAIMED,
                saved.userId(),
                saved.couponCode(),
                "couponId=" + saved.couponId());
        return MarketingDtoAssembler.toResponse(saved);
    }

    private SeckillOrderResponseDto createSeckillOrderLocked(SeckillRequestDto request, String key) {
        Optional<SeckillOrder> existing = marketingStore.findSeckillOrder(request.activityId(), request.userId(), key);
        if (existing.isPresent()) {
            return MarketingDtoAssembler.toResponse(existing.get());
        }
        idempotencyStore.reserve("seckill:" + request.activityId(), request.userId(), key, "order", IDEMPOTENCY_TTL);
        SeckillActivity activity = marketingStore
                .findSeckillActivity(request.activityId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Seckill activity does not exist"));
        SeckillActivity savedActivity = marketingStore.saveSeckillActivity(activity.reserve(
                request.quantity(), marketingStore.purchasedQuantity(activity.id(), request.userId()), now()));
        SeckillOrder order = new SeckillOrder(
                idGenerator.nextId(),
                savedActivity.id(),
                savedActivity.skuId(),
                request.userId(),
                request.orderId(),
                request.quantity(),
                "seckill:" + key,
                now());
        SeckillOrder saved = marketingStore.saveSeckillOrder(order);
        audit(
                AuditService.MARKETING_SECKILL_ORDERED,
                saved.userId(),
                Long.toString(saved.activityId()),
                "quantity=" + saved.quantity());
        return MarketingDtoAssembler.toResponse(saved);
    }

    private GroupBuyTeamResponseDto joinGroupBuyLocked(GroupBuyJoinRequestDto request, String key) {
        GroupBuyActivity activity = marketingStore
                .findGroupBuyActivity(request.activityId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Group-buy activity does not exist"));
        if (!activity.active()) {
            throw new BusinessException(ErrorCode.CONFLICT, "Group-buy activity is inactive");
        }
        GroupBuyTeam team =
                request.teamId() == null ? newGroupBuyTeam(activity, request.userId()) : requireTeam(request.teamId());
        if (marketingStore.hasGroupBuyMember(team.id(), request.userId())) {
            return MarketingDtoAssembler.toResponse(team);
        }
        GroupBuyTeam saved = request.teamId() == null
                ? marketingStore.saveGroupBuyTeam(team)
                : marketingStore.saveGroupBuyTeam(team.join());
        marketingStore.saveGroupBuyMember(idGenerator.nextId(), saved.id(), request.userId(), "group:" + key, now());
        audit(
                AuditService.MARKETING_GROUP_JOINED,
                request.userId(),
                Long.toString(saved.id()),
                "status=" + saved.status());
        return MarketingDtoAssembler.toResponse(saved);
    }

    private GroupBuyTeam newGroupBuyTeam(GroupBuyActivity activity, Long userId) {
        return new GroupBuyTeam(
                idGenerator.nextId(),
                activity.id(),
                activity.skuId(),
                userId,
                activity.targetSize(),
                1,
                activity.targetSize() == 1 ? GroupBuyStatus.SUCCEEDED : GroupBuyStatus.OPEN,
                now().plusHours(activity.durationHours()));
    }

    private GroupBuyTeam requireTeam(Long teamId) {
        return marketingStore
                .findGroupBuyTeam(teamId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Group-buy team does not exist"));
    }

    private UserCoupon requireUserCoupon(String code) {
        if (!StringUtils.hasText(code)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "couponCode is required");
        }
        return marketingStore
                .findUserCouponByCode(code)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "User coupon does not exist"));
    }

    private void validateHuman(String token, String clientIp) {
        if (captchaService != null
                && captchaService.externalProviderEnabled()
                && !captchaService.validate(null, token, TURNSTILE_ACTION_SECKILL, clientIp)) {
            auditService.record(
                    AuditService.MARKETING_SECKILL_DENIED,
                    AuditService.OUTCOME_DENIED,
                    null,
                    "CUSTOMER",
                    "seckill",
                    clientIp,
                    "reason=turnstile");
            throw new BusinessException(ErrorCode.FORBIDDEN, "Human verification failed");
        }
    }

    private void audit(String eventType, Long userId, String subject, String detail) {
        auditService.record(eventType, AuditService.OUTCOME_SUCCESS, userId, "CUSTOMER", subject, null, detail);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private static String normalizeKey(String key, String label) {
        if (!StringUtils.hasText(key)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, label + " is required");
        }
        String trimmed = key.trim();
        if (trimmed.length() > 120) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, label + " is too long");
        }
        return trimmed;
    }
}
