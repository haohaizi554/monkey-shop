package com.example.monkey.order.application;

import com.example.monkey.order.application.dto.OrderPageQuery;
import com.example.monkey.order.application.dto.OrderResponseDto;
import com.example.monkey.order.application.dto.OrderReviewRequestDto;
import com.example.monkey.order.application.dto.OrderReviewResponseDto;
import com.example.monkey.order.application.dto.OrderShipmentLineRequestDto;
import com.example.monkey.order.application.dto.OrderShipmentRequestDto;
import com.example.monkey.order.application.dto.OrderShipmentResponseDto;
import com.example.monkey.order.application.observability.BusinessMetricsService;
import com.example.monkey.order.domain.OrderCustomerPort;
import com.example.monkey.order.domain.OrderEvent;
import com.example.monkey.order.domain.OrderFulfillmentItem;
import com.example.monkey.order.domain.OrderFulfillmentStore;
import com.example.monkey.order.domain.OrderIdempotencyStore.IdempotencyReservationRecord;
import com.example.monkey.order.domain.OrderLockManager;
import com.example.monkey.order.domain.OrderNumberGenerator;
import com.example.monkey.order.domain.OrderProductPort;
import com.example.monkey.order.domain.OrderReview;
import com.example.monkey.order.domain.OrderShipmentBatch;
import com.example.monkey.order.domain.OrderShipmentLine;
import com.example.monkey.order.domain.OrderShipmentStatus;
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
import com.example.monkey.shared.domain.id.IdGenerator;
import com.example.monkey.shared.domain.storage.ImageReferenceService;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
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
    private static final String SYSTEM_ROLE = "SYSTEM";
    private static final String DEFAULT_CARRIER = "MonkeyExpress";
    private static final int AUTO_RECEIVE_BATCH_SIZE = 100;
    private static final OrderPageRequest LEGACY_ORDER_LIST_REQUEST =
            new OrderPageRequest(0, LEGACY_LIST_PAGE_SIZE, List.of(new SortOrder("createTime", Direction.DESC)));

    private final OrderStore orderStore;
    private final OrderProductPort orderProductPort;
    private final OrderCustomerPort orderCustomerPort;
    private final OrderFulfillmentStore fulfillmentStore;
    private final OrderNumberGenerator orderNumberGenerator;
    private final IdGenerator idGenerator;
    private final OrderIdempotencyService orderIdempotencyService;
    private final OrderLockManager orderLockManager;
    private final OrderTransitionResolver orderTransitionResolver;
    private final TransactionOperations transactionOperations;
    private final ImageReferenceService imageReferenceService;
    private final BusinessMetricsService businessMetricsService;
    private final AuditService auditService;
    private final Clock clock;
    private final Duration autoReceiveAfter;

    public OrderService(
            OrderStore orderStore,
            OrderProductPort orderProductPort,
            OrderCustomerPort orderCustomerPort,
            OrderNumberGenerator orderNumberGenerator,
            OrderIdempotencyService orderIdempotencyService,
            OrderLockManager orderLockManager,
            OrderTransitionResolver orderTransitionResolver,
            TransactionOperations transactionOperations,
            ImageReferenceService imageReferenceService,
            BusinessMetricsService businessMetricsService,
            AuditService auditService,
            OrderFulfillmentStore fulfillmentStore,
            IdGenerator idGenerator,
            @Value("${app.order.auto-receive-after:P7D}") Duration autoReceiveAfter) {
        this.orderStore = orderStore;
        this.orderProductPort = orderProductPort;
        this.orderCustomerPort = orderCustomerPort;
        this.fulfillmentStore = fulfillmentStore;
        this.orderNumberGenerator = orderNumberGenerator;
        this.idGenerator = idGenerator;
        this.orderIdempotencyService = orderIdempotencyService;
        this.orderLockManager = orderLockManager;
        this.orderTransitionResolver = orderTransitionResolver;
        this.transactionOperations = transactionOperations;
        this.imageReferenceService = imageReferenceService;
        this.businessMetricsService = businessMetricsService;
        this.auditService = auditService;
        this.clock = Clock.systemDefaultZone();
        this.autoReceiveAfter = autoReceiveAfter == null ? Duration.ofDays(7) : autoReceiveAfter;
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

        ProductRecord product = orderProductPort
                .findProductById(monkeyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Product does not exist"));
        AddressRecord address = orderCustomerPort
                .findAddressById(addressId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Address does not exist"));
        BuyerRecord buyer = orderCustomerPort
                .findBuyerById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "User does not exist"));

        ensureOrderPlaceable(userId, buyer, product, address);
        if (!orderProductPort.deductProductStock(monkeyId)) {
            businessMetricsService.recordStockDeductFailure();
            throw new BusinessException(ErrorCode.OUT_OF_STOCK, "Insufficient stock");
        }

        OrderRecord order = OrderRecord.place(orderNumberGenerator.nextOrderNo(), userId, buyer, product, address);
        OrderRecord savedOrder = orderStore.savePlacedOrder(order);
        OrderRecord completedOrder = savedOrder != null ? savedOrder : order;
        imageReferenceService.retain(completedOrder.productImage());
        imageReferenceService.retain(completedOrder.buyerAvatar());
        orderIdempotencyService.complete(userId, normalizedIdempotencyKey, completedOrder.id());
        businessMetricsService.recordOrderCreated();
        auditOrderCreated(completedOrder, userId);
        return OrderDtoAssembler.toResponse(completedOrder);
    }

    private void ensureOrderPlaceable(Long userId, BuyerRecord buyer, ProductRecord product, AddressRecord address) {
        try {
            OrderRecord.ensurePlaceable(userId, buyer, product, address);
        } catch (BusinessException e) {
            if (e.errorCode() == ErrorCode.OUT_OF_STOCK) {
                businessMetricsService.recordStockDeductFailure();
            }
            throw e;
        }
    }

    @Transactional
    public OrderResponseDto shipOrder(Long orderId) {
        OrderRecord order = requireOrder(orderId);
        ShipmentResult result = shipOrderBatch(order, new OrderShipmentRequestDto(null, null, List.of()));
        return OrderDtoAssembler.toResponse(order.withStatus(result.nextStatus(), result.shippingTime()));
    }

    @WithSpan("order.shipment.create")
    @Transactional
    public OrderShipmentResponseDto shipOrder(Long orderId, OrderShipmentRequestDto request) {
        OrderRecord order = requireOrder(orderId);
        ShipmentResult result =
                shipOrderBatch(order, request == null ? new OrderShipmentRequestDto(null, null, List.of()) : request);
        return OrderDtoAssembler.toResponse(result.shipment());
    }

    @Transactional
    public OrderResponseDto receiveOrder(Long orderId, Long userId) {
        OrderRecord order = requireOwnedOrder(orderId, userId);
        List<OrderShipmentBatch> shipments = fulfillmentStore.findShipments(order.id());
        if (!shipments.isEmpty()) {
            for (OrderShipmentBatch shipment : shipments) {
                if (OrderShipmentStatus.SHIPPED.equals(shipment.status())) {
                    receiveShipmentBatch(order, shipment, userId, USER_ROLE);
                    order = requireOwnedOrder(orderId, userId);
                }
            }
            return OrderDtoAssembler.toResponse(requireOwnedOrder(orderId, userId));
        }
        return transitionAndMap(order, OrderEvent.RECEIVE, null, userId, USER_ROLE);
    }

    @WithSpan("order.shipment.receive")
    @Transactional
    public OrderShipmentResponseDto receiveShipment(Long shipmentId, Long userId) {
        OrderShipmentBatch shipment = fulfillmentStore
                .findShipment(shipmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Shipment does not exist"));
        OrderRecord order = requireOwnedOrder(shipment.orderId(), userId);
        return OrderDtoAssembler.toResponse(receiveShipmentBatch(order, shipment, userId, USER_ROLE));
    }

    @Transactional(readOnly = true)
    public List<OrderShipmentResponseDto> findShipments(Long orderId, Long userId) {
        requireOwnedOrder(orderId, userId);
        return fulfillmentStore.findShipments(orderId).stream()
                .map(OrderDtoAssembler::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<OrderShipmentResponseDto> findShipmentsAsAdmin(Long orderId) {
        requireOrder(orderId);
        return fulfillmentStore.findShipments(orderId).stream()
                .map(OrderDtoAssembler::toResponse)
                .toList();
    }

    @WithSpan("order.review.create")
    @Transactional
    public OrderReviewResponseDto reviewOrder(Long orderId, Long userId, OrderReviewRequestDto request) {
        OrderRecord order = requireOwnedOrder(orderId, userId);
        if (!OrderStatus.COMPLETED.equals(statusOf(order))) {
            throw new BusinessException(ErrorCode.CONFLICT, "Order must be completed before review");
        }
        Long skuId = request.skuId() == null ? order.productId() : request.skuId();
        if (fulfillmentStore.hasReview(order.id(), userId, skuId)) {
            throw new BusinessException(ErrorCode.CONFLICT, "Order line has already been reviewed");
        }
        OrderReview review = new OrderReview(
                idGenerator.nextId(),
                order.id(),
                userId,
                skuId,
                request.rating(),
                normalizeText(request.content(), 1000),
                request.imageUrls().stream()
                        .filter(StringUtils::hasText)
                        .map(String::trim)
                        .toList(),
                request.anonymous(),
                now());
        OrderReview saved = fulfillmentStore.saveReview(review);
        saved.imageUrls().forEach(imageReferenceService::retain);
        auditService.record(
                AuditService.ORDER_REVIEWED,
                AuditService.OUTCOME_SUCCESS,
                userId,
                USER_ROLE,
                auditSubject(order),
                null,
                "orderId=" + order.id() + ",skuId=" + skuId + ",rating=" + request.rating());
        return OrderDtoAssembler.toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<OrderReviewResponseDto> findReviews(Long orderId, Long userId) {
        requireOwnedOrder(orderId, userId);
        return fulfillmentStore.findReviews(orderId).stream()
                .map(OrderDtoAssembler::toResponse)
                .toList();
    }

    @Scheduled(fixedDelayString = "${app.order.auto-receive-delay:PT5M}")
    @SchedulerLock(
            name = "order-auto-receive-shipments",
            lockAtMostFor = "${app.order.auto-receive-lock-at-most-for:PT10M}")
    @Transactional
    public void autoReceiveOverdueShipmentsScheduled() {
        autoReceiveOverdueShipments();
    }

    @Transactional
    public int autoReceiveOverdueShipments() {
        LocalDateTime cutoff = now().minus(autoReceiveAfter);
        int received = 0;
        for (OrderShipmentBatch shipment : fulfillmentStore.findReceivableShipments(cutoff, AUTO_RECEIVE_BATCH_SIZE)) {
            OrderRecord order = requireOrder(shipment.orderId());
            receiveShipmentBatch(order, shipment, null, SYSTEM_ROLE);
            received++;
        }
        return received;
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
        if (!orderProductPort.restoreProductStock(productId)) {
            throw new BusinessException(ErrorCode.CONFLICT, "Product snapshot is missing or stale");
        }
    }

    private ShipmentResult shipOrderBatch(OrderRecord order, OrderShipmentRequestDto request) {
        OrderStatus currentStatus = statusOf(order);
        assertShippingAllowed(currentStatus);
        List<ShipmentPlanLine> planLines = shipmentPlan(order, request.lines());
        LocalDateTime shippedAt = now();
        Long shipmentId = idGenerator.nextId();
        OrderShipmentBatch shipment = new OrderShipmentBatch(
                shipmentId,
                order.id(),
                "SHP" + shipmentId,
                normalizeText(request.carrier(), 64, DEFAULT_CARRIER),
                normalizeText(request.trackingNo(), 96, "TRK" + shipmentId),
                OrderShipmentStatus.SHIPPED,
                shippedAt,
                null,
                planLines.stream()
                        .map(plan -> new OrderShipmentLine(
                                idGenerator.nextId(),
                                shipmentId,
                                order.id(),
                                plan.item().skuId(),
                                plan.item().productName(),
                                plan.quantity()))
                        .toList());
        for (ShipmentPlanLine planLine : planLines) {
            fulfillmentStore.saveItem(planLine.item().ship(planLine.quantity()));
        }
        OrderShipmentBatch savedShipment = fulfillmentStore.saveShipment(shipment);
        boolean allShipped = allItemsShipped(order.id());
        OrderEvent event = allShipped ? OrderEvent.SHIP : OrderEvent.SHIP_PARTIAL;
        OrderStatus nextStatus = orderTransitionResolver.nextStatus(currentStatus, event);
        LocalDateTime shippingTime = OrderStatus.SHIPPED.equals(nextStatus) ? shippedAt : null;
        transitionAndMap(order, currentStatus, nextStatus, shippingTime, event, null, ADMIN_ROLE);
        auditShipment(savedShipment, allShipped ? "all-shipped" : "partial-shipped", null, ADMIN_ROLE);
        return new ShipmentResult(savedShipment, nextStatus, shippingTime);
    }

    private OrderShipmentBatch receiveShipmentBatch(
            OrderRecord order, OrderShipmentBatch shipment, Long actorUserId, String actorRole) {
        if (OrderShipmentStatus.RECEIVED.equals(shipment.status())) {
            return shipment;
        }
        LocalDateTime receivedAt = now();
        for (OrderShipmentLine line : shipment.lines()) {
            OrderFulfillmentItem item = fulfillmentStore
                    .findItem(order.id(), line.skuId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT, "Fulfillment item is missing"));
            fulfillmentStore.saveItem(item.receive(line.quantity()));
        }
        OrderShipmentBatch receivedShipment = fulfillmentStore.markShipmentReceived(shipment.receive(receivedAt));
        boolean allReceived = allItemsReceived(order.id());
        OrderEvent event = allReceived ? OrderEvent.RECEIVE : OrderEvent.RECEIVE_PARTIAL;
        OrderStatus currentStatus = statusOf(order);
        OrderStatus nextStatus = orderTransitionResolver.nextStatus(currentStatus, event);
        transitionAndMap(order, currentStatus, nextStatus, null, event, actorUserId, actorRole);
        auditShipment(receivedShipment, allReceived ? "all-received" : "partial-received", actorUserId, actorRole);
        return receivedShipment;
    }

    private List<ShipmentPlanLine> shipmentPlan(OrderRecord order, List<OrderShipmentLineRequestDto> requestedLines) {
        if (requestedLines == null || requestedLines.isEmpty()) {
            OrderFulfillmentItem item = ensureFulfillmentItem(order, order.productId(), order.productName(), 1);
            return List.of(new ShipmentPlanLine(item, item.unshippedQuantity()));
        }
        List<ShipmentPlanLine> planLines = new ArrayList<>();
        for (OrderShipmentLineRequestDto line : requestedLines) {
            Long skuId = line.skuId() == null ? order.productId() : line.skuId();
            int orderedQuantity = line.orderedQuantity() > 0 ? line.orderedQuantity() : line.quantity();
            OrderFulfillmentItem item = ensureFulfillmentItem(
                    order,
                    skuId,
                    StringUtils.hasText(line.productName()) ? line.productName() : order.productName(),
                    orderedQuantity);
            planLines.add(new ShipmentPlanLine(item, line.quantity()));
        }
        return planLines;
    }

    private static void assertShippingAllowed(OrderStatus currentStatus) {
        boolean shippable =
                OrderTransitionPolicy.nextStatus(currentStatus, OrderEvent.SHIP).isPresent()
                        || OrderTransitionPolicy.nextStatus(currentStatus, OrderEvent.SHIP_PARTIAL)
                                .isPresent();
        if (!shippable) {
            throw new BusinessException(ErrorCode.CONFLICT, OrderTransitionPolicy.STATUS_TRANSITION_NOT_ALLOWED);
        }
    }

    private OrderFulfillmentItem ensureFulfillmentItem(
            OrderRecord order, Long skuId, String productName, int orderedQuantity) {
        return fulfillmentStore
                .findItem(order.id(), skuId)
                .orElseGet(() -> fulfillmentStore.saveItem(new OrderFulfillmentItem(
                        idGenerator.nextId(), order.id(), skuId, productName, orderedQuantity, 0, 0, "PENDING")));
    }

    private boolean allItemsShipped(Long orderId) {
        List<OrderFulfillmentItem> items = fulfillmentStore.findItems(orderId);
        return !items.isEmpty() && items.stream().allMatch(item -> item.shippedQuantity() == item.orderedQuantity());
    }

    private boolean allItemsReceived(Long orderId) {
        List<OrderFulfillmentItem> items = fulfillmentStore.findItems(orderId);
        return !items.isEmpty() && items.stream().allMatch(item -> item.receivedQuantity() == item.orderedQuantity());
    }

    private void auditShipment(OrderShipmentBatch shipment, String phase, Long actorUserId, String actorRole) {
        auditService.record(
                OrderShipmentStatus.RECEIVED.equals(shipment.status())
                        ? AuditService.ORDER_SHIPMENT_RECEIVED
                        : AuditService.ORDER_SHIPMENT_CREATED,
                AuditService.OUTCOME_SUCCESS,
                actorUserId,
                actorRole,
                shipment.shipmentNo(),
                null,
                "orderId=" + shipment.orderId() + ",phase=" + phase + ",trackingNo=" + shipment.trackingNo());
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
            case SHIP_PARTIAL -> AuditService.ORDER_PARTIALLY_SHIPPED;
            case SHIP -> AuditService.ORDER_SHIPPED;
            case RECEIVE_PARTIAL -> AuditService.ORDER_PARTIALLY_RECEIVED;
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

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private static String normalizeText(String value, int maxLength) {
        return normalizeText(value, maxLength, null);
    }

    private static String normalizeText(String value, int maxLength, String defaultValue) {
        String normalized = StringUtils.hasText(value) ? value.trim() : defaultValue;
        if (normalized == null || normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength);
    }

    private record ShipmentResult(OrderShipmentBatch shipment, OrderStatus nextStatus, LocalDateTime shippingTime) {}

    private record ShipmentPlanLine(OrderFulfillmentItem item, int quantity) {}

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
