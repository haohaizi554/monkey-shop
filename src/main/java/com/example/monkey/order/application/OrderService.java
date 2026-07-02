package com.example.monkey.order.application;

import com.example.monkey.order.application.dto.OrderPageQuery;
import com.example.monkey.order.application.dto.OrderResponseDto;
import com.example.monkey.order.application.observability.BusinessMetricsService;
import com.example.monkey.order.domain.OrderEvent;
import com.example.monkey.order.domain.OrderIdempotencyStore.IdempotencyReservationRecord;
import com.example.monkey.order.domain.OrderLockManager;
import com.example.monkey.order.domain.OrderNumberGenerator;
import com.example.monkey.order.domain.OrderStatus;
import com.example.monkey.order.domain.OrderStore;
import com.example.monkey.order.domain.OrderStore.AddressRecord;
import com.example.monkey.order.domain.OrderStore.BuyerRecord;
import com.example.monkey.order.domain.OrderStore.OrderPage;
import com.example.monkey.order.domain.OrderStore.OrderPageRequest;
import com.example.monkey.order.domain.OrderStore.OrderRecord;
import com.example.monkey.order.domain.OrderStore.ProductRecord;
import com.example.monkey.order.domain.OrderStore.SortOrder;
import com.example.monkey.order.domain.OrderStore.SortOrder.Direction;
import com.example.monkey.order.domain.OrderTransitionPolicy;
import com.example.monkey.order.domain.OrderTransitionResolver;
import com.example.monkey.shared.application.dto.PageResponseDto;
import com.example.monkey.shared.application.observability.AuditService;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.shared.domain.storage.ImageReferenceService;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.util.StringUtils;

@Service
public class OrderService {

    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;
    private static final int LEGACY_LIST_PAGE_SIZE = 100;
    private static final Pattern IDEMPOTENCY_KEY_PATTERN = Pattern.compile("[A-Za-z0-9._:-]+");
    private static final String ADMIN_ROLE = "ADMIN";
    private static final String USER_ROLE = "USER";
    private static final OrderPageRequest LEGACY_ORDER_LIST_REQUEST =
            new OrderPageRequest(0, LEGACY_LIST_PAGE_SIZE, List.of(new SortOrder("createTime", Direction.DESC)));

    private final OrderStore orderStore;
    private final OrderNumberGenerator orderNumberGenerator;
    private final OrderIdempotencyService orderIdempotencyService;
    private final OrderLockManager orderLockManager;
    private final OrderTransitionResolver orderTransitionResolver;
    private final TransactionOperations transactionOperations;
    private final ImageReferenceService imageReferenceService;
    private final BusinessMetricsService businessMetricsService;
    private final AuditService auditService;

    public OrderService(
            OrderStore orderStore,
            OrderNumberGenerator orderNumberGenerator,
            OrderIdempotencyService orderIdempotencyService,
            OrderLockManager orderLockManager,
            OrderTransitionResolver orderTransitionResolver,
            TransactionOperations transactionOperations,
            ImageReferenceService imageReferenceService,
            BusinessMetricsService businessMetricsService,
            AuditService auditService) {
        this.orderStore = orderStore;
        this.orderNumberGenerator = orderNumberGenerator;
        this.orderIdempotencyService = orderIdempotencyService;
        this.orderLockManager = orderLockManager;
        this.orderTransitionResolver = orderTransitionResolver;
        this.transactionOperations = transactionOperations;
        this.imageReferenceService = imageReferenceService;
        this.businessMetricsService = businessMetricsService;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<OrderResponseDto> findOrdersForUser(Long userId) {
        return orderStore.findVisibleByUser(userId, LEGACY_ORDER_LIST_REQUEST).content().stream()
                .map(OrderDtoAssembler::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public PageResponseDto<OrderResponseDto> findOrdersForUser(Long userId, OrderPageQuery pageQuery) {
        OrderPage page = orderStore.findVisibleByUser(userId, toOrderPageRequest(pageQuery));
        return toPageResponse(page);
    }

    @Transactional(readOnly = true)
    public List<OrderResponseDto> findAllOrders() {
        return orderStore.findAll(LEGACY_ORDER_LIST_REQUEST).content().stream()
                .map(OrderDtoAssembler::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public PageResponseDto<OrderResponseDto> findAllOrders(OrderPageQuery pageQuery) {
        return toPageResponse(orderStore.findAll(toOrderPageRequest(pageQuery)));
    }

    @WithSpan("order.create")
    public OrderResponseDto createOrder(Long userId, Long monkeyId, Long addressId, String idempotencyKey) {
        try {
            return businessMetricsService.recordOrderCreate(() -> {
                String normalizedIdempotencyKey = normalizeIdempotencyKey(idempotencyKey);
                return orderLockManager.withCreateOrderLock(
                        userId,
                        monkeyId,
                        () -> transactionOperations.execute(status ->
                                createOrderInTransaction(userId, monkeyId, addressId, normalizedIdempotencyKey)));
            });
        } catch (BusinessException e) {
            auditOrderCreateFailure(userId, monkeyId, e.errorCode().code());
            throw e;
        } catch (RuntimeException e) {
            auditOrderCreateFailure(userId, monkeyId, ErrorCode.INTERNAL_ERROR.code());
            throw e;
        }
    }

    private OrderResponseDto createOrderInTransaction(
            Long userId, Long monkeyId, Long addressId, String normalizedIdempotencyKey) {
        String requestHash = requestHash(monkeyId, addressId);
        OrderIdempotencyService.Reservation reservation =
                orderIdempotencyService.reserve(userId, normalizedIdempotencyKey, requestHash);
        if (!reservation.reserved()) {
            return resolveDuplicateOrder(reservation.record(), requestHash);
        }

        ProductRecord product = orderStore
                .findProductById(monkeyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Product does not exist"));
        AddressRecord address = orderStore
                .findAddressById(addressId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Address does not exist"));
        BuyerRecord buyer = orderStore
                .findBuyerById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "User does not exist"));

        if (!address.userId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Address does not belong to current user");
        }

        if (!product.hasStock()) {
            businessMetricsService.recordStockDeductFailure();
            throw new BusinessException(ErrorCode.OUT_OF_STOCK, "Insufficient stock");
        }
        if (!orderStore.deductProductStock(monkeyId)) {
            businessMetricsService.recordStockDeductFailure();
            throw new BusinessException(ErrorCode.OUT_OF_STOCK, "Insufficient stock");
        }

        OrderRecord order = OrderRecord.place(orderNumberGenerator.nextOrderNo(), buyer, product, address);
        OrderRecord savedOrder = orderStore.savePlacedOrder(order);
        OrderRecord completedOrder = savedOrder != null ? savedOrder : order;
        imageReferenceService.retain(completedOrder.productImage());
        imageReferenceService.retain(completedOrder.buyerAvatar());
        orderIdempotencyService.complete(userId, normalizedIdempotencyKey, completedOrder.id());
        businessMetricsService.recordOrderCreated();
        auditOrderCreated(completedOrder, userId);
        return OrderDtoAssembler.toResponse(completedOrder);
    }

    @Transactional
    public OrderResponseDto shipOrder(Long orderId) {
        OrderRecord order = requireOrder(orderId);
        return transitionAndMap(order, OrderEvent.SHIP, LocalDateTime.now(), null, ADMIN_ROLE);
    }

    @Transactional
    public OrderResponseDto receiveOrder(Long orderId, Long userId) {
        OrderRecord order = requireOwnedOrder(orderId, userId);
        return transitionAndMap(order, OrderEvent.RECEIVE, null, userId, USER_ROLE);
    }

    @Transactional
    public void hideOrderForUser(Long orderId, Long userId) {
        OrderRecord order = requireOwnedOrder(orderId, userId);
        orderStore.hideFromUser(order.id());
        auditOrderHidden(order, userId);
    }

    @Transactional
    public OrderResponseDto applyReturn(Long orderId, Long userId) {
        OrderRecord order = requireOwnedOrder(orderId, userId);
        return transitionAndMap(order, OrderEvent.REQUEST_RETURN, null, userId, USER_ROLE);
    }

    @Transactional
    public OrderResponseDto approveReturn(Long orderId) {
        OrderRecord order = requireOrder(orderId);
        return transitionAndMap(order, OrderEvent.APPROVE_RETURN, null, null, ADMIN_ROLE);
    }

    @Transactional
    public OrderResponseDto shipReturn(Long orderId, Long userId) {
        OrderRecord order = requireOwnedOrder(orderId, userId);
        return transitionAndMap(order, OrderEvent.SHIP_RETURN, null, userId, USER_ROLE);
    }

    @Transactional
    public OrderResponseDto confirmReturn(Long orderId) {
        OrderRecord order = requireOrder(orderId);
        OrderStatus currentStatus = statusOf(order);
        OrderStatus nextStatus = orderTransitionResolver.nextStatus(currentStatus, OrderEvent.REFUND);
        restoreStockForOrder(order);
        return transitionAndMap(order, currentStatus, nextStatus, null, OrderEvent.REFUND, null, ADMIN_ROLE);
    }

    private OrderRecord requireOrder(Long orderId) {
        return orderStore
                .findById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Order does not exist"));
    }

    private OrderRecord requireOwnedOrder(Long orderId, Long userId) {
        return orderStore
                .findVisibleByIdAndUserId(orderId, userId)
                .orElseThrow(
                        () -> new BusinessException(ErrorCode.FORBIDDEN, "Order is not available for current user"));
    }

    private void restoreStockForOrder(OrderRecord order) {
        Long productId = order.productId();
        Long orderId = order.id();
        if (productId == null || orderId == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "Product snapshot is missing or stale");
        }
        if (!orderStore.recordStockRestore(orderId, productId)) {
            return;
        }
        if (!orderStore.restoreProductStock(productId)) {
            throw new BusinessException(ErrorCode.CONFLICT, "Product snapshot is missing or stale");
        }
    }

    private OrderResponseDto resolveDuplicateOrder(IdempotencyReservationRecord record, String requestHash) {
        if (record == null) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "Idempotency state is temporarily unavailable");
        }
        if (!requestHash.equals(record.requestHash())) {
            throw new BusinessException(ErrorCode.CONFLICT, "Idempotency-Key was already used for a different request");
        }
        if (record.isCompleted() && record.orderId() != null) {
            OrderRecord order = orderStore
                    .findById(record.orderId())
                    .orElseThrow(() ->
                            new BusinessException(ErrorCode.CONFLICT, "Idempotency record references a missing order"));
            return OrderDtoAssembler.toResponse(order);
        }
        throw new BusinessException(ErrorCode.CONFLICT, "Duplicate order request is already in progress");
    }

    private OrderResponseDto transitionAndMap(
            OrderRecord order, OrderEvent event, LocalDateTime shippingTime, Long actorUserId, String actorRole) {
        OrderStatus currentStatus = statusOf(order);
        OrderStatus nextStatus = orderTransitionResolver.nextStatus(currentStatus, event);
        return transitionAndMap(order, currentStatus, nextStatus, shippingTime, event, actorUserId, actorRole);
    }

    private OrderResponseDto transitionAndMap(
            OrderRecord order,
            OrderStatus currentStatus,
            OrderStatus nextStatus,
            LocalDateTime shippingTime,
            OrderEvent event,
            Long actorUserId,
            String actorRole) {
        int rows = orderStore.transitionStatus(order.id(), currentStatus.label(), nextStatus.label(), shippingTime);
        if (rows == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, OrderTransitionPolicy.STATUS_TRANSITION_NOT_ALLOWED);
        }
        OrderRecord transitionedOrder = order.withStatus(nextStatus, shippingTime);
        auditOrderTransition(transitionedOrder, event, currentStatus, nextStatus, actorUserId, actorRole);
        return OrderDtoAssembler.toResponse(transitionedOrder);
    }

    private void auditOrderCreated(OrderRecord order, Long actorUserId) {
        auditService.record(
                AuditService.ORDER_CREATED,
                AuditService.OUTCOME_SUCCESS,
                actorUserId,
                USER_ROLE,
                auditSubject(order),
                null,
                "orderId=" + order.id() + " status=" + statusOf(order).name());
    }

    private void auditOrderCreateFailure(Long actorUserId, Long monkeyId, String reason) {
        auditService.record(
                AuditService.ORDER_CREATE_FAILURE,
                AuditService.OUTCOME_FAILURE,
                actorUserId,
                USER_ROLE,
                "order-create:" + actorUserId + ":" + monkeyId,
                null,
                "monkeyId=" + monkeyId + " reason=" + reason);
    }

    private void auditOrderHidden(OrderRecord order, Long actorUserId) {
        auditService.record(
                AuditService.ORDER_HIDDEN,
                AuditService.OUTCOME_SUCCESS,
                actorUserId,
                USER_ROLE,
                auditSubject(order),
                null,
                "orderId=" + order.id() + " status=" + statusOf(order).name());
    }

    private void auditOrderTransition(
            OrderRecord order,
            OrderEvent event,
            OrderStatus currentStatus,
            OrderStatus nextStatus,
            Long actorUserId,
            String actorRole) {
        auditService.record(
                auditEventType(event),
                AuditService.OUTCOME_SUCCESS,
                actorUserId,
                actorRole,
                auditSubject(order),
                null,
                "orderId=" + order.id() + " from=" + currentStatus.name() + " to=" + nextStatus.name());
    }

    private static String auditEventType(OrderEvent event) {
        return switch (event) {
            case SHIP -> AuditService.ORDER_SHIPPED;
            case RECEIVE -> AuditService.ORDER_RECEIVED;
            case REQUEST_RETURN -> AuditService.ORDER_RETURN_REQUESTED;
            case APPROVE_RETURN -> AuditService.ORDER_RETURN_APPROVED;
            case SHIP_RETURN -> AuditService.ORDER_RETURN_SHIPPED;
            case REFUND -> AuditService.ORDER_REFUNDED;
        };
    }

    private static String auditSubject(OrderRecord order) {
        return StringUtils.hasText(order.orderNo()) ? order.orderNo() : String.valueOf(order.id());
    }

    private static OrderStatus statusOf(OrderRecord order) {
        try {
            return OrderStatus.fromStoredValue(order.status());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.CONFLICT, OrderTransitionPolicy.STATUS_TRANSITION_NOT_ALLOWED);
        }
    }

    private static OrderPageRequest toOrderPageRequest(OrderPageQuery pageQuery) {
        List<SortOrder> sortOrders = pageQuery.sortOrders().stream()
                .map(OrderService::toDomainSortOrder)
                .toList();
        return new OrderPageRequest(pageQuery.page(), pageQuery.size(), sortOrders);
    }

    private static SortOrder toDomainSortOrder(OrderPageQuery.SortOrder sortOrder) {
        Direction direction =
                sortOrder.direction() == OrderPageQuery.SortOrder.Direction.DESC ? Direction.DESC : Direction.ASC;
        return new SortOrder(sortOrder.property(), direction);
    }

    private static PageResponseDto<OrderResponseDto> toPageResponse(OrderPage page) {
        return PageResponseDto.from(
                page.content().stream().map(OrderDtoAssembler::toResponse).toList(),
                page.page(),
                page.size(),
                page.totalElements(),
                page.totalPages(),
                page.first(),
                page.last());
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

    private static String requestHash(Long monkeyId, Long addressId) {
        return sha256Hex(monkeyId + ":" + addressId);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest is unavailable", e);
        }
    }
}
