package com.example.monkey.marketing.application;

import com.example.monkey.marketing.application.dto.CouponClaimRequestDto;
import com.example.monkey.marketing.application.dto.CouponRedeemRequestDto;
import com.example.monkey.marketing.application.dto.CouponResponseDto;
import com.example.monkey.marketing.application.dto.CouponReturnRequestDto;
import com.example.monkey.marketing.application.dto.GroupBuyJoinRequestDto;
import com.example.monkey.marketing.application.dto.GroupBuyTeamResponseDto;
import com.example.monkey.marketing.application.dto.MarketingPriceAllocationDto;
import com.example.monkey.marketing.application.dto.MarketingPriceLineDto;
import com.example.monkey.marketing.application.dto.MarketingPriceQuoteDto;
import com.example.monkey.marketing.application.dto.MarketingPriceRequestDto;
import com.example.monkey.marketing.application.dto.SeckillOrderResponseDto;
import com.example.monkey.marketing.application.dto.SeckillRequestDto;
import com.example.monkey.marketing.domain.CouponDefinition;
import com.example.monkey.marketing.domain.CouponStatus;
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
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
import org.springframework.transaction.annotation.Propagation;
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
    public CouponResponseDto claimCoupon(CouponClaimRequestDto request, Long currentUserId) {
        if (currentUserId == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Coupon claim requires the current user");
        }
        String key = normalizeKey(request.idempotencyKey(), "idempotency key");
        return lockManager.withCouponLock(request.couponId(), () -> claimCouponLocked(request, key, currentUserId));
    }

    @WithSpan("marketing.coupon.redeem")
    @Transactional
    public CouponResponseDto redeemCoupon(CouponRedeemRequestDto request, Long currentUserId) {
        Long userId = requireCurrentUserId(currentUserId);
        String couponCode = normalizeCouponCode(request.couponCode());
        boolean changed = marketingStore.redeemUserCouponForOrder(userId, couponCode, request.orderId(), now());
        UserCoupon coupon = requireUserCoupon(userId, couponCode);
        if (!changed
                && !(coupon.isRedeemed()
                        && request.orderId().equals(coupon.orderId())
                        && coupon.checkoutId() == null)) {
            throw new BusinessException(ErrorCode.CONFLICT, "Coupon is already bound to another transaction");
        }
        if (changed) {
            audit(
                    AuditService.MARKETING_COUPON_REDEEMED,
                    coupon.userId(),
                    coupon.couponCode(),
                    "orderId=" + coupon.orderId());
        }
        return MarketingDtoAssembler.toResponse(coupon);
    }

    @WithSpan("marketing.coupon.return")
    @Transactional
    public CouponResponseDto returnCoupon(CouponReturnRequestDto request, Long currentUserId) {
        Long userId = requireCurrentUserId(currentUserId);
        String couponCode = normalizeCouponCode(request.couponCode());
        boolean changed = marketingStore.returnUserCouponForOrder(userId, couponCode, request.orderId());
        UserCoupon coupon = requireUserCoupon(userId, couponCode);
        if (!changed
                && !(CouponStatus.CLAIMED.equals(coupon.status())
                        && request.orderId().equals(coupon.orderId())
                        && coupon.checkoutId() == null)) {
            throw new BusinessException(ErrorCode.CONFLICT, "Coupon is not bound to this transaction");
        }
        if (changed) {
            audit(
                    AuditService.MARKETING_COUPON_RETURNED,
                    coupon.userId(),
                    coupon.couponCode(),
                    "orderId=" + request.orderId());
        }
        return MarketingDtoAssembler.toResponse(coupon);
    }

    @WithSpan("marketing.coupon.checkout.redeem")
    @Transactional(propagation = Propagation.MANDATORY)
    public void redeemForCheckout(Long userId, Long checkoutId, List<String> couponCodes) {
        requireCheckoutIdentity(userId, checkoutId);
        List<String> normalizedCodes = couponCodes == null
                ? List.of()
                : couponCodes.stream()
                        .map(MarketingApplicationService::normalizeCouponCode)
                        .distinct()
                        .toList();
        for (String couponCode : normalizedCodes) {
            boolean changed = marketingStore.redeemUserCouponForCheckout(userId, couponCode, checkoutId, now());
            UserCoupon coupon = requireUserCoupon(userId, couponCode);
            if (!changed
                    && !(coupon.isRedeemed() && checkoutId.equals(coupon.checkoutId()) && coupon.orderId() == null)) {
                throw new BusinessException(ErrorCode.CONFLICT, "Coupon is already bound to another transaction");
            }
            if (changed) {
                audit(
                        AuditService.MARKETING_COUPON_REDEEMED,
                        coupon.userId(),
                        coupon.couponCode(),
                        "checkoutId=" + checkoutId);
            }
        }
    }

    @WithSpan("marketing.coupon.checkout.return")
    @Transactional(propagation = Propagation.MANDATORY)
    public void returnForCheckout(Long userId, Long checkoutId, String reason) {
        requireCheckoutIdentity(userId, checkoutId);
        int returned = marketingStore.returnUserCouponsForCheckout(userId, checkoutId);
        if (returned > 0) {
            audit(
                    AuditService.MARKETING_COUPON_RETURNED,
                    userId,
                    "checkout:" + checkoutId,
                    "count=" + returned + ",reason=" + normalizeReason(reason));
        }
    }

    @WithSpan("marketing.price.quote")
    @Transactional(readOnly = true)
    public MarketingPriceQuoteDto quotePrice(MarketingPriceRequestDto request) {
        return quotePrice(request, coupon -> matchesRequestScope(coupon, request));
    }

    @WithSpan("marketing.price.quote")
    @Transactional(readOnly = true)
    public MarketingPriceQuoteDto quotePrice(MarketingPriceRequestDto request, Long currentUserId) {
        if (currentUserId != null) {
            if (request.userId() != null && !currentUserId.equals(request.userId())) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "Price quote must use the current user");
            }
            return quotePrice(request.withUserId(currentUserId));
        }
        return quotePrice(request);
    }

    @WithSpan("marketing.price.quote.platform")
    @Transactional(readOnly = true)
    public MarketingPriceQuoteDto quotePlatformPrice(MarketingPriceRequestDto request) {
        return quotePrice(request, MarketingApplicationService::isPlatformCoupon);
    }

    @WithSpan("marketing.price.quote.store")
    @Transactional(readOnly = true)
    public MarketingPriceQuoteDto quoteStorePrice(MarketingPriceRequestDto request) {
        return quotePrice(request, coupon -> !isPlatformCoupon(coupon) && matchesRequestScope(coupon, request));
    }

    private MarketingPriceQuoteDto quotePrice(MarketingPriceRequestDto request, Predicate<CouponDefinition> eligible) {
        if (!request.lines().isEmpty()) {
            return quotePriceByLine(request, eligible);
        }
        return quoteAggregatePrice(request, eligible);
    }

    private MarketingPriceQuoteDto quoteAggregatePrice(
            MarketingPriceRequestDto request, Predicate<CouponDefinition> eligible) {
        BigDecimal orderAmount = request.orderAmount();
        Map<String, CouponDefinition> selected = new LinkedHashMap<>();
        for (String code : request.couponCodes()) {
            CouponDefinition coupon = marketingStore
                    .findCouponByCode(code)
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Coupon does not exist"));
            requireCouponAvailableToUser(request.userId(), coupon);
            BigDecimal couponDiscount = coupon.discountFor(orderAmount);
            if (eligible.test(coupon) && couponDiscount.compareTo(BigDecimal.ZERO) > 0) {
                CouponDefinition previous = selected.get(coupon.stackGroup());
                if (previous == null || couponDiscount.compareTo(previous.discountFor(orderAmount)) > 0) {
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

    private MarketingPriceQuoteDto quotePriceByLine(
            MarketingPriceRequestDto request, Predicate<CouponDefinition> eligible) {
        validatePriceLines(request);
        Map<String, CouponCandidate> selected = new LinkedHashMap<>();
        for (String code : request.couponCodes()) {
            CouponDefinition coupon = marketingStore
                    .findCouponByCode(code)
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Coupon does not exist"));
            requireCouponAvailableToUser(request.userId(), coupon);
            if (!eligible.test(coupon)) {
                continue;
            }
            List<MarketingPriceLineDto> eligibleLines = request.lines().stream()
                    .filter(line -> coupon.matches(line.categoryId(), line.shopId()))
                    .toList();
            BigDecimal eligibleAmount =
                    eligibleLines.stream().map(MarketingPriceLineDto::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal couponDiscount = coupon.discountFor(eligibleAmount);
            if (couponDiscount.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            CouponCandidate candidate = new CouponCandidate(coupon, eligibleLines, couponDiscount);
            CouponCandidate previous = selected.get(coupon.stackGroup());
            if (previous == null || couponDiscount.compareTo(previous.discount()) > 0) {
                selected.put(coupon.stackGroup(), candidate);
            }
        }

        Map<Long, BigDecimal> discountsByLine = new LinkedHashMap<>();
        Map<Long, List<String>> couponsByLine = new LinkedHashMap<>();
        for (MarketingPriceLineDto line : request.lines()) {
            discountsByLine.put(line.lineId(), money(BigDecimal.ZERO));
            couponsByLine.put(line.lineId(), new ArrayList<>());
        }

        List<String> appliedCoupons = new ArrayList<>();
        List<CouponCandidate> candidates = selected.values().stream()
                .sorted(Comparator.comparing(candidate -> candidate.coupon().stackGroup()))
                .toList();
        for (CouponCandidate candidate : candidates) {
            List<BigDecimal> remainingAmounts = candidate.lines().stream()
                    .map(line -> money(line.amount()
                            .subtract(discountsByLine.get(line.lineId()))
                            .max(BigDecimal.ZERO)))
                    .toList();
            List<BigDecimal> allocations = allocateDiscount(candidate.discount(), remainingAmounts);
            BigDecimal allocatedTotal = allocations.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            if (allocatedTotal.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            appliedCoupons.add(candidate.coupon().code());
            for (int index = 0; index < candidate.lines().size(); index++) {
                BigDecimal allocation = allocations.get(index);
                if (allocation.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                Long lineId = candidate.lines().get(index).lineId();
                discountsByLine.compute(lineId, (ignored, current) -> money(current.add(allocation)));
                couponsByLine.get(lineId).add(candidate.coupon().code());
            }
        }

        List<MarketingPriceAllocationDto> lineAllocations = request.lines().stream()
                .map(line -> new MarketingPriceAllocationDto(
                        line.lineId(), discountsByLine.get(line.lineId()), couponsByLine.get(line.lineId())))
                .toList();
        BigDecimal discount = lineAllocations.stream()
                .map(MarketingPriceAllocationDto::discountAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal payable = money(request.orderAmount().subtract(discount).max(BigDecimal.ZERO));
        return new MarketingPriceQuoteDto(
                money(request.orderAmount()), money(discount), payable, appliedCoupons, lineAllocations);
    }

    private static boolean matchesRequestScope(CouponDefinition coupon, MarketingPriceRequestDto request) {
        if (request.lines().isEmpty()) {
            return coupon.matches(request.categoryId(), request.shopId());
        }
        return request.lines().stream().anyMatch(line -> coupon.matches(line.categoryId(), line.shopId()));
    }

    private static void validatePriceLines(MarketingPriceRequestDto request) {
        long distinctLineIds = request.lines().stream()
                .map(MarketingPriceLineDto::lineId)
                .distinct()
                .count();
        if (distinctLineIds != request.lines().size()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Price line identifiers must be unique");
        }
        BigDecimal lineTotal =
                request.lines().stream().map(MarketingPriceLineDto::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (money(lineTotal).compareTo(money(request.orderAmount())) != 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Price line total must equal the order amount");
        }
    }

    private static List<BigDecimal> allocateDiscount(BigDecimal discountAmount, List<BigDecimal> bases) {
        BigDecimal total =
                bases.stream().map(MarketingApplicationService::money).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal remainingDiscount = money(discountAmount).min(total);
        BigDecimal remainingBase = total;
        List<BigDecimal> allocations = new ArrayList<>();
        for (int index = 0; index < bases.size(); index++) {
            BigDecimal base = money(bases.get(index));
            BigDecimal allocation;
            if (remainingDiscount.compareTo(BigDecimal.ZERO) <= 0 || base.compareTo(BigDecimal.ZERO) <= 0) {
                allocation = money(BigDecimal.ZERO);
            } else if (index == bases.size() - 1 || remainingBase.compareTo(BigDecimal.ZERO) <= 0) {
                allocation = remainingDiscount.min(base);
            } else {
                allocation = money(remainingDiscount.multiply(base).divide(remainingBase, 8, RoundingMode.HALF_UP))
                        .min(base)
                        .min(remainingDiscount);
            }
            allocations.add(money(allocation));
            remainingDiscount = remainingDiscount.subtract(allocation);
            remainingBase = remainingBase.subtract(base);
        }
        return allocations;
    }

    private static BigDecimal money(BigDecimal amount) {
        return (amount == null ? BigDecimal.ZERO : amount).setScale(2, RoundingMode.HALF_UP);
    }

    private record CouponCandidate(CouponDefinition coupon, List<MarketingPriceLineDto> lines, BigDecimal discount) {}

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

    @WithSpan("marketing.seckill.order")
    @Transactional
    public SeckillOrderResponseDto createSeckillOrder(SeckillRequestDto request, Long currentUserId, String clientIp) {
        return createSeckillOrder(effectiveSeckillRequest(request, currentUserId), clientIp);
    }

    private SeckillRequestDto effectiveSeckillRequest(SeckillRequestDto request, Long currentUserId) {
        if (currentUserId == null) {
            return request;
        }
        if (request.userId() != null && !currentUserId.equals(request.userId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Seckill order must belong to the current user");
        }
        return request.withUserId(currentUserId);
    }

    @WithSpan("marketing.group-buy.join")
    @Transactional
    public GroupBuyTeamResponseDto joinGroupBuy(GroupBuyJoinRequestDto request) {
        Long lockKey = request.teamId() == null ? request.activityId() : request.teamId();
        String key = normalizeKey(request.idempotencyKey(), "idempotency key");
        return lockManager.withGroupBuyLock(lockKey, () -> joinGroupBuyLocked(request, key));
    }

    @WithSpan("marketing.group-buy.join")
    @Transactional
    public GroupBuyTeamResponseDto joinGroupBuy(GroupBuyJoinRequestDto request, Long currentUserId) {
        return joinGroupBuy(effectiveGroupBuyRequest(request, currentUserId));
    }

    private GroupBuyJoinRequestDto effectiveGroupBuyRequest(GroupBuyJoinRequestDto request, Long currentUserId) {
        if (currentUserId == null) {
            return request;
        }
        if (request.userId() != null && !currentUserId.equals(request.userId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Group buy must belong to the current user");
        }
        return request.withUserId(currentUserId);
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

    private CouponResponseDto claimCouponLocked(CouponClaimRequestDto request, String key, Long claimOwner) {
        Optional<UserCoupon> existing = marketingStore.findUserCoupon(claimOwner, request.couponId());
        if (existing.isPresent()) {
            return MarketingDtoAssembler.toResponse(existing.get());
        }
        idempotencyStore.reserve("coupon:" + request.couponId(), claimOwner, key, "claim", IDEMPOTENCY_TTL);
        CouponDefinition coupon = marketingStore
                .findCoupon(request.couponId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Coupon does not exist"));
        CouponDefinition savedCoupon = marketingStore.saveCoupon(coupon.reserveClaim(now()));
        UserCoupon userCoupon = new UserCoupon(
                idGenerator.nextId(),
                savedCoupon.id(),
                savedCoupon.code(),
                claimOwner,
                com.example.monkey.marketing.domain.CouponStatus.CLAIMED,
                null,
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

    private UserCoupon requireUserCoupon(Long userId, String code) {
        return marketingStore
                .findUserCouponByCode(userId, code)
                .orElseThrow(
                        () -> new BusinessException(ErrorCode.FORBIDDEN, "Coupon does not belong to the current user"));
    }

    private void requireCouponAvailableToUser(Long userId, CouponDefinition coupon) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Coupon quote requires the current user");
        }
        UserCoupon userCoupon = marketingStore
                .findUserCoupon(userId, coupon.id())
                .orElseThrow(
                        () -> new BusinessException(ErrorCode.FORBIDDEN, "Coupon does not belong to the current user"));
        LocalDateTime currentTime = now();
        if (!CouponStatus.CLAIMED.equals(userCoupon.status())
                || currentTime.isBefore(coupon.startTime())
                || !currentTime.isBefore(coupon.endTime())) {
            throw new BusinessException(ErrorCode.CONFLICT, "Coupon is not available");
        }
    }

    private static Long requireCurrentUserId(Long currentUserId) {
        if (currentUserId == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Coupon mutation requires the current user");
        }
        return currentUserId;
    }

    private static void requireCheckoutIdentity(Long userId, Long checkoutId) {
        if (userId == null || userId <= 0 || checkoutId == null || checkoutId <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Valid userId and checkoutId are required");
        }
    }

    private static String normalizeReason(String reason) {
        return StringUtils.hasText(reason) ? reason.trim() : "UNSPECIFIED";
    }

    private static String normalizeCouponCode(String code) {
        if (!StringUtils.hasText(code)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "couponCode is required");
        }
        return code.trim();
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
