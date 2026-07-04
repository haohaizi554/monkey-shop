package com.example.monkey.cart.application;

import static com.example.monkey.shared.application.security.AuthenticatedPrincipals.requireUserId;

import com.example.monkey.cart.application.dto.CartAddItemRequestDto;
import com.example.monkey.cart.application.dto.CartCheckoutRequestDto;
import com.example.monkey.cart.application.dto.CartCheckoutResponseDto;
import com.example.monkey.cart.application.dto.CartResponseDto;
import com.example.monkey.cart.application.dto.CartSelectItemRequestDto;
import com.example.monkey.cart.application.dto.CartUpdateItemRequestDto;
import com.example.monkey.cart.domain.CartCatalogReader;
import com.example.monkey.cart.domain.CartCheckoutStatus;
import com.example.monkey.cart.domain.CartCheckoutStore;
import com.example.monkey.cart.domain.CartItem;
import com.example.monkey.cart.domain.CartLockManager;
import com.example.monkey.cart.domain.CartSkuSnapshot;
import com.example.monkey.cart.domain.CartSnapshot;
import com.example.monkey.cart.domain.CartStore;
import com.example.monkey.cart.domain.CheckoutLine;
import com.example.monkey.cart.domain.CheckoutOrder;
import com.example.monkey.cart.domain.CheckoutSubOrder;
import com.example.monkey.inventory.application.InventoryApplicationService;
import com.example.monkey.inventory.application.dto.InventoryReservationResponseDto;
import com.example.monkey.inventory.application.dto.InventoryReserveRequestDto;
import com.example.monkey.marketing.application.MarketingApplicationService;
import com.example.monkey.marketing.application.dto.MarketingPriceQuoteDto;
import com.example.monkey.marketing.application.dto.MarketingPriceRequestDto;
import com.example.monkey.order.domain.OrderNumberGenerator;
import com.example.monkey.shared.application.observability.AuditService;
import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.shared.domain.id.IdGenerator;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class CartApplicationService {

    private static final Duration DEFAULT_CART_TTL = Duration.ofDays(7);
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;
    private static final Pattern IDEMPOTENCY_KEY_PATTERN = Pattern.compile("[A-Za-z0-9._:-]+");
    private static final String CUSTOMER_ROLE = "CUSTOMER";

    private final CartStore cartStore;
    private final CartCatalogReader catalogReader;
    private final CartCheckoutStore checkoutStore;
    private final CartLockManager lockManager;
    private final InventoryApplicationService inventoryApplicationService;
    private final MarketingApplicationService marketingApplicationService;
    private final OrderNumberGenerator orderNumberGenerator;
    private final IdGenerator idGenerator;
    private final AuditService auditService;
    private final Clock clock;
    private final Duration cartTtl;

    @Autowired
    public CartApplicationService(
            CartStore cartStore,
            CartCatalogReader catalogReader,
            CartCheckoutStore checkoutStore,
            CartLockManager lockManager,
            InventoryApplicationService inventoryApplicationService,
            MarketingApplicationService marketingApplicationService,
            OrderNumberGenerator orderNumberGenerator,
            IdGenerator idGenerator,
            AuditService auditService,
            @Value("${app.cart.ttl:PT168H}") Duration cartTtl) {
        this(
                cartStore,
                catalogReader,
                checkoutStore,
                lockManager,
                inventoryApplicationService,
                marketingApplicationService,
                orderNumberGenerator,
                idGenerator,
                auditService,
                Clock.systemDefaultZone(),
                cartTtl);
    }

    CartApplicationService(
            CartStore cartStore,
            CartCatalogReader catalogReader,
            CartCheckoutStore checkoutStore,
            CartLockManager lockManager,
            InventoryApplicationService inventoryApplicationService,
            MarketingApplicationService marketingApplicationService,
            OrderNumberGenerator orderNumberGenerator,
            IdGenerator idGenerator,
            AuditService auditService,
            Clock clock,
            Duration cartTtl) {
        this.cartStore = cartStore;
        this.catalogReader = catalogReader;
        this.checkoutStore = checkoutStore;
        this.lockManager = lockManager;
        this.inventoryApplicationService = inventoryApplicationService;
        this.marketingApplicationService = marketingApplicationService;
        this.orderNumberGenerator = orderNumberGenerator;
        this.idGenerator = idGenerator;
        this.auditService = auditService;
        this.clock = clock;
        this.cartTtl = cartTtl == null ? DEFAULT_CART_TTL : cartTtl;
    }

    @WithSpan("cart.get")
    @Transactional(readOnly = true)
    public CartResponseDto cart(SessionUser currentUser) {
        CartSnapshot cart = cartStore.findCart(requireUserId(currentUser));
        return CartDtoAssembler.toResponse(cart, skuSnapshots(cart.items()));
    }

    @WithSpan("cart.add")
    @Transactional
    public CartResponseDto addItem(SessionUser currentUser, CartAddItemRequestDto request) {
        Long userId = requireUserId(currentUser);
        requireSku(request.skuId());
        CartSnapshot saved = cartStore.save(
                cartStore
                        .findCart(userId)
                        .upsert(request.skuId(), request.shopId(), request.quantity(), request.selected(), now()),
                cartTtl);
        audit(AuditService.CART_ITEM_CHANGED, userId, "skuId=" + request.skuId() + ",quantity=" + request.quantity());
        return CartDtoAssembler.toResponse(saved, skuSnapshots(saved.items()));
    }

    @WithSpan("cart.quantity")
    @Transactional
    public CartResponseDto updateItem(SessionUser currentUser, Long skuId, CartUpdateItemRequestDto request) {
        Long userId = requireUserId(currentUser);
        CartSnapshot current = requireCartItem(cartStore.findCart(userId), skuId);
        CartSnapshot saved = cartStore.save(current.changeQuantity(skuId, request.quantity(), now()), cartTtl);
        audit(AuditService.CART_ITEM_CHANGED, userId, "skuId=" + skuId + ",quantity=" + request.quantity());
        return CartDtoAssembler.toResponse(saved, skuSnapshots(saved.items()));
    }

    @WithSpan("cart.select")
    @Transactional
    public CartResponseDto selectItem(SessionUser currentUser, Long skuId, CartSelectItemRequestDto request) {
        Long userId = requireUserId(currentUser);
        CartSnapshot current = requireCartItem(cartStore.findCart(userId), skuId);
        CartSnapshot saved = cartStore.save(current.select(skuId, request.selected(), now()), cartTtl);
        audit(AuditService.CART_ITEM_CHANGED, userId, "skuId=" + skuId + ",selected=" + request.selected());
        return CartDtoAssembler.toResponse(saved, skuSnapshots(saved.items()));
    }

    @WithSpan("cart.remove")
    @Transactional
    public CartResponseDto removeItem(SessionUser currentUser, Long skuId) {
        Long userId = requireUserId(currentUser);
        CartSnapshot current = requireCartItem(cartStore.findCart(userId), skuId);
        CartSnapshot saved = cartStore.save(current.remove(skuId), cartTtl);
        audit(AuditService.CART_ITEM_CHANGED, userId, "skuId=" + skuId + ",removed=true");
        return CartDtoAssembler.toResponse(saved, skuSnapshots(saved.items()));
    }

    @WithSpan("cart.checkout.preview")
    @Transactional(readOnly = true)
    public CartCheckoutResponseDto previewCheckout(SessionUser currentUser, CartCheckoutRequestDto request) {
        Long userId = requireUserId(currentUser);
        CartSnapshot cart = cartStore.findCart(userId);
        CheckoutOrder preview = buildCheckout(userId, request, "preview:" + requestHash(cart), false);
        return CartDtoAssembler.toResponse(preview);
    }

    @WithSpan("cart.checkout")
    @Transactional
    public CartCheckoutResponseDto checkout(
            SessionUser currentUser, CartCheckoutRequestDto request, String idempotencyKey) {
        Long userId = requireUserId(currentUser);
        String key = normalizeIdempotencyKey(idempotencyKey);
        return lockManager.withCheckoutLock(
                userId, key, () -> CartDtoAssembler.toResponse(checkoutLocked(userId, request, key)));
    }

    private CheckoutOrder checkoutLocked(Long userId, CartCheckoutRequestDto request, String idempotencyKey) {
        Optional<CheckoutOrder> existing = checkoutStore.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }
        CheckoutOrder checkout = buildCheckout(userId, request, idempotencyKey, true);
        CheckoutOrder saved = checkoutStore.save(checkout);
        cartStore.removeItems(
                userId,
                checkout.subOrders().stream()
                        .flatMap(subOrder -> subOrder.lines().stream())
                        .map(CheckoutLine::skuId)
                        .toList(),
                cartTtl);
        audit(
                AuditService.CART_CHECKOUT_CREATED,
                userId,
                "checkoutId=" + saved.id() + ",subOrders=" + saved.subOrders().size());
        return saved;
    }

    private CheckoutOrder buildCheckout(
            Long userId, CartCheckoutRequestDto request, String idempotencyKey, boolean reserveInventory) {
        List<CartItem> selectedItems = cartStore.findCart(userId).selectedItems();
        if (selectedItems.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "No selected cart items to checkout");
        }
        Long checkoutId = idGenerator.nextId();
        CartCheckoutStatus status = reserveInventory ? CartCheckoutStatus.CHECKED_OUT : CartCheckoutStatus.RESERVED;
        List<ResolvedCartLine> lines = selectedItems.stream()
                .map(item -> resolveLine(checkoutId, userId, request, idempotencyKey, item, reserveInventory))
                .toList();
        List<CheckoutSubOrder> subOrders = splitByShop(userId, lines, status);
        BigDecimal originalAmount = sumSubOrders(subOrders, CheckoutSubOrder::originalAmount);
        BigDecimal discountAmount = sumSubOrders(subOrders, CheckoutSubOrder::discountAmount);
        BigDecimal payableAmount = sumSubOrders(subOrders, CheckoutSubOrder::payableAmount);
        return new CheckoutOrder(
                checkoutId,
                reserveInventory ? orderNumberGenerator.nextOrderNo() : "PREVIEW-" + checkoutId,
                userId,
                request.addressId(),
                idempotencyKey,
                originalAmount,
                discountAmount,
                payableAmount,
                status,
                request.province(),
                now(),
                subOrders);
    }

    private ResolvedCartLine resolveLine(
            Long checkoutId,
            Long userId,
            CartCheckoutRequestDto request,
            String idempotencyKey,
            CartItem item,
            boolean reserveInventory) {
        CartSkuSnapshot sku = requireSku(item.skuId());
        String reservationKey = "cart:" + userId + ":" + idempotencyKey + ":" + item.skuId();
        Long warehouseId = null;
        if (reserveInventory) {
            InventoryReservationResponseDto reservation =
                    inventoryApplicationService.reserve(new InventoryReserveRequestDto(
                            item.skuId(), null, request.province(), checkoutId, item.quantity(), reservationKey));
            warehouseId = reservation.warehouseId();
        }
        BigDecimal originalAmount = money(sku.salePrice().multiply(BigDecimal.valueOf(item.quantity())));
        return new ResolvedCartLine(
                idGenerator.nextId(),
                item.shopId(),
                sku,
                item.quantity(),
                originalAmount,
                request.couponCodes(),
                reservationKey,
                warehouseId);
    }

    private List<CheckoutSubOrder> splitByShop(Long userId, List<ResolvedCartLine> lines, CartCheckoutStatus status) {
        Map<Long, List<ResolvedCartLine>> byShop = new LinkedHashMap<>();
        for (ResolvedCartLine line : lines) {
            byShop.computeIfAbsent(line.shopId(), ignored -> new ArrayList<>()).add(line);
        }
        Map<Long, List<PricedCartLine>> pricedByShop = new LinkedHashMap<>();
        List<PricedCartLine> pricedLines = new ArrayList<>();
        for (Map.Entry<Long, List<ResolvedCartLine>> entry : byShop.entrySet()) {
            List<PricedCartLine> shopLines = priceStoreDiscounts(userId, entry.getKey(), entry.getValue());
            pricedByShop.put(entry.getKey(), shopLines);
            pricedLines.addAll(shopLines);
        }
        MarketingPriceQuoteDto platformQuote = quotePlatformDiscount(userId, lines);
        Map<Long, BigDecimal> platformDiscounts = allocatePlatformDiscount(platformQuote.discountAmount(), pricedLines);
        List<CheckoutSubOrder> subOrders = new ArrayList<>();
        for (Map.Entry<Long, List<PricedCartLine>> entry : pricedByShop.entrySet()) {
            subOrders.add(toSubOrder(entry.getKey(), entry.getValue(), status, platformQuote, platformDiscounts));
        }
        return subOrders;
    }

    private List<PricedCartLine> priceStoreDiscounts(Long userId, Long shopId, List<ResolvedCartLine> lines) {
        BigDecimal originalAmount =
                lines.stream().map(ResolvedCartLine::originalAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        MarketingPriceQuoteDto quote = marketingApplicationService.quoteStorePrice(new MarketingPriceRequestDto(
                originalAmount, userId, lines.get(0).sku().categoryId(), shopId, couponCodes(lines)));
        List<BigDecimal> discounts = allocateDiscount(quote.discountAmount(), lines);
        List<PricedCartLine> priced = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            priced.add(new PricedCartLine(lines.get(index), discounts.get(index), quote.appliedCoupons()));
        }
        return priced;
    }

    private MarketingPriceQuoteDto quotePlatformDiscount(Long userId, List<ResolvedCartLine> lines) {
        BigDecimal originalAmount =
                lines.stream().map(ResolvedCartLine::originalAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return marketingApplicationService.quotePlatformPrice(
                new MarketingPriceRequestDto(originalAmount, userId, null, null, couponCodes(lines)));
    }

    private CheckoutSubOrder toSubOrder(
            Long shopId,
            List<PricedCartLine> lines,
            CartCheckoutStatus status,
            MarketingPriceQuoteDto platformQuote,
            Map<Long, BigDecimal> platformDiscounts) {
        BigDecimal originalAmount =
                lines.stream().map(line -> line.line().originalAmount()).reduce(BigDecimal.ZERO, BigDecimal::add);
        Long subOrderId = idGenerator.nextId();
        List<CheckoutLine> checkoutLines = new ArrayList<>();
        for (PricedCartLine pricedLine : lines) {
            ResolvedCartLine line = pricedLine.line();
            BigDecimal platformDiscount =
                    platformDiscounts.getOrDefault(line.id(), BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
            BigDecimal discount =
                    money(pricedLine.storeDiscount().add(platformDiscount).min(line.originalAmount()));
            checkoutLines.add(new CheckoutLine(
                    line.id(),
                    line.sku().skuId(),
                    line.shopId(),
                    line.sku().categoryId(),
                    line.sku().productName(),
                    line.sku().productImage(),
                    line.quantity(),
                    line.sku().salePrice(),
                    line.originalAmount(),
                    discount,
                    money(line.originalAmount().subtract(discount)),
                    appliedCoupons(pricedLine, platformQuote, platformDiscount),
                    line.reservationKey(),
                    line.warehouseId()));
        }
        BigDecimal discountAmount =
                checkoutLines.stream().map(CheckoutLine::discountAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal payableAmount =
                checkoutLines.stream().map(CheckoutLine::payableAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new CheckoutSubOrder(
                subOrderId,
                shopId,
                orderNumberGenerator.nextOrderNo(),
                money(originalAmount),
                money(discountAmount),
                money(payableAmount),
                status,
                checkoutLines);
    }

    private static List<String> couponCodes(List<ResolvedCartLine> lines) {
        return lines.stream()
                .flatMap(line -> line.couponCodes().stream())
                .distinct()
                .toList();
    }

    private static List<String> appliedCoupons(
            PricedCartLine pricedLine, MarketingPriceQuoteDto platformQuote, BigDecimal platformDiscount) {
        List<String> coupons = new ArrayList<>();
        if (pricedLine.storeDiscount().compareTo(BigDecimal.ZERO) > 0) {
            coupons.addAll(pricedLine.storeCoupons());
        }
        if (platformDiscount.compareTo(BigDecimal.ZERO) > 0) {
            coupons.addAll(platformQuote.appliedCoupons());
        }
        return coupons.stream().distinct().toList();
    }

    private CartSkuSnapshot requireSku(Long skuId) {
        return catalogReader
                .findActiveSku(skuId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "SKU does not exist or is not active"));
    }

    private CartSnapshot requireCartItem(CartSnapshot cart, Long skuId) {
        boolean exists = cart.items().stream().anyMatch(item -> item.skuId().equals(skuId));
        if (!exists) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Cart item does not exist");
        }
        return cart;
    }

    private Map<Long, CartSkuSnapshot> skuSnapshots(List<CartItem> items) {
        Map<Long, CartSkuSnapshot> snapshots = new HashMap<>();
        for (CartItem item : items) {
            catalogReader.findActiveSku(item.skuId()).ifPresent(sku -> snapshots.put(item.skuId(), sku));
        }
        return snapshots;
    }

    private void audit(String eventType, Long userId, String detail) {
        auditService.record(
                eventType, AuditService.OUTCOME_SUCCESS, userId, CUSTOMER_ROLE, "cart:" + userId, null, detail);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private static BigDecimal sumSubOrders(
            List<CheckoutSubOrder> subOrders, java.util.function.Function<CheckoutSubOrder, BigDecimal> mapper) {
        return money(subOrders.stream().map(mapper).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private static List<BigDecimal> allocateDiscount(BigDecimal discountAmount, List<ResolvedCartLine> lines) {
        return allocateDiscountByBase(
                discountAmount,
                lines.stream().map(ResolvedCartLine::originalAmount).toList());
    }

    private static Map<Long, BigDecimal> allocatePlatformDiscount(
            BigDecimal discountAmount, List<PricedCartLine> lines) {
        List<BigDecimal> discounts = allocateDiscountByBase(
                discountAmount,
                lines.stream().map(PricedCartLine::remainingAmount).toList());
        Map<Long, BigDecimal> byLineId = new HashMap<>();
        for (int index = 0; index < lines.size(); index++) {
            byLineId.put(lines.get(index).line().id(), discounts.get(index));
        }
        return byLineId;
    }

    private static List<BigDecimal> allocateDiscountByBase(BigDecimal discountAmount, List<BigDecimal> bases) {
        BigDecimal discount = money(discountAmount == null ? BigDecimal.ZERO : discountAmount);
        if (discount.compareTo(BigDecimal.ZERO) <= 0 || bases.isEmpty()) {
            return bases.stream()
                    .map(ignored -> BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                    .toList();
        }
        BigDecimal total = bases.stream().map(CartApplicationService::money).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(BigDecimal.ZERO) <= 0) {
            return bases.stream()
                    .map(ignored -> BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                    .toList();
        }
        BigDecimal remaining = discount.min(total);
        List<BigDecimal> discounts = new ArrayList<>();
        for (int index = 0; index < bases.size(); index++) {
            BigDecimal lineDiscount;
            BigDecimal base = money(bases.get(index));
            if (index == bases.size() - 1) {
                lineDiscount = remaining;
            } else {
                lineDiscount = money(base.multiply(discount).divide(total, 8, RoundingMode.HALF_UP));
                lineDiscount = lineDiscount.min(base).min(remaining);
            }
            discounts.add(money(lineDiscount));
            remaining = remaining.subtract(lineDiscount);
        }
        return discounts;
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

    private static String requestHash(CartSnapshot cart) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (CartItem item : cart.selectedItems()) {
                digest.update((item.skuId() + ":" + item.quantity() + ";").getBytes(StandardCharsets.UTF_8));
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest is unavailable", e);
        }
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    private record ResolvedCartLine(
            Long id,
            Long shopId,
            CartSkuSnapshot sku,
            int quantity,
            BigDecimal originalAmount,
            List<String> couponCodes,
            String reservationKey,
            Long warehouseId) {
        ResolvedCartLine {
            couponCodes = couponCodes == null ? List.of() : List.copyOf(couponCodes);
        }
    }

    private record PricedCartLine(ResolvedCartLine line, BigDecimal storeDiscount, List<String> storeCoupons) {
        PricedCartLine {
            storeDiscount = money(storeDiscount);
            storeCoupons = storeCoupons == null ? List.of() : List.copyOf(storeCoupons);
        }

        BigDecimal remainingAmount() {
            return money(line.originalAmount().subtract(storeDiscount));
        }
    }
}
