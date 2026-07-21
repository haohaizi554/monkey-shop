package com.example.monkey.order.interfaces;

import com.example.monkey.order.application.OrderApplicationService;
import com.example.monkey.order.application.OrderService;
import com.example.monkey.order.application.dto.OrderPageQuery;
import com.example.monkey.order.application.dto.OrderPageQuery.SortOrder;
import com.example.monkey.order.application.dto.OrderPageQuery.SortOrder.Direction;
import com.example.monkey.order.application.dto.OrderResponseDto;
import com.example.monkey.order.application.dto.OrderReviewRequestDto;
import com.example.monkey.order.application.dto.OrderReviewResponseDto;
import com.example.monkey.order.application.dto.OrderShipmentRequestDto;
import com.example.monkey.order.application.dto.OrderShipmentResponseDto;
import com.example.monkey.order.interfaces.dto.CreateOrderRequestDto;
import com.example.monkey.risk.application.RiskApplicationService;
import com.example.monkey.risk.application.dto.RiskAssessmentRequestDto;
import com.example.monkey.shared.application.dto.PageResponseDto;
import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.interfaces.dto.Result;
import com.example.monkey.shared.interfaces.web.ClientIps;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/orders", "/api/v1/orders"})
public class OrderController {

    private final OrderApplicationService orderApplicationService;
    private final OrderService orderService;
    private final RiskApplicationService riskApplicationService;

    public OrderController(
            OrderApplicationService orderApplicationService,
            OrderService orderService,
            RiskApplicationService riskApplicationService) {
        this.orderApplicationService = orderApplicationService;
        this.orderService = orderService;
        this.riskApplicationService = riskApplicationService;
    }

    @PostMapping("/create")
    @PreAuthorize("hasAuthority('ORDER_CREATE')")
    public Result<OrderResponseDto> createOrder(
            @RequestHeader(value = "Idempotency-Key") @NotBlank String idempotencyKey,
            @RequestHeader(value = "X-Device-Fingerprint", required = false) String deviceFingerprint,
            @Valid @RequestBody CreateOrderRequestDto requestBody,
            @AuthenticationPrincipal SessionUser currentUser,
            HttpServletRequest httpRequest) {
        riskApplicationService.requireAllowed(
                currentUser,
                new RiskAssessmentRequestDto(
                        null, deviceFingerprint, null, requestBody.monkeyId(), null, null, null, null, null, null),
                ClientIps.resolve(httpRequest),
                "order.create");
        return Result.success(orderApplicationService.createOrder(
                currentUser, requestBody.monkeyId(), requestBody.addressId(), idempotencyKey));
    }

    @GetMapping("/my")
    @PreAuthorize("hasAuthority('ORDER_READ_OWN')")
    public Result<PageResponseDto<OrderResponseDto>> myOrders(
            @ParameterObject @PageableDefault(size = 20, sort = "createTime", direction = Sort.Direction.DESC)
                    Pageable pageable,
            @AuthenticationPrincipal SessionUser currentUser) {
        return Result.success(orderApplicationService.findOrders(currentUser, toOrderPageQuery(pageable)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('ORDER_READ_OWN') and @orderOwnership.isOwner(#id, authentication)")
    public Result<OrderResponseDto> order(@PathVariable Long id, @AuthenticationPrincipal SessionUser currentUser) {
        return Result.success(orderApplicationService.findOrder(currentUser, id));
    }

    @GetMapping("/all")
    @PreAuthorize("hasAuthority('ORDER_MANAGE')")
    public Result<PageResponseDto<OrderResponseDto>> getAllOrders(
            @ParameterObject @PageableDefault(size = 20, sort = "createTime", direction = Sort.Direction.DESC)
                    Pageable pageable) {
        return Result.success(orderService.findAllOrders(toOrderPageQuery(pageable)));
    }

    @PostMapping("/ship/{id}")
    @PreAuthorize("hasAuthority('ORDER_MANAGE')")
    public Result<OrderResponseDto> shipOrder(@PathVariable Long id) {
        return Result.success(orderService.shipOrder(id));
    }

    @PostMapping("/shipments/{id}")
    @PreAuthorize("hasAuthority('ORDER_MANAGE')")
    public Result<OrderShipmentResponseDto> shipOrder(
            @PathVariable Long id, @Valid @RequestBody OrderShipmentRequestDto request) {
        return Result.success(orderService.shipOrder(id, request));
    }

    @GetMapping("/{id}/shipments")
    @PreAuthorize("hasAuthority('ORDER_READ_OWN') and @orderOwnership.isOwner(#id, authentication)")
    public Result<List<OrderShipmentResponseDto>> shipments(
            @PathVariable Long id, @AuthenticationPrincipal SessionUser currentUser) {
        return Result.success(orderApplicationService.findShipments(currentUser, id));
    }

    @GetMapping("/admin/{id}/shipments")
    @PreAuthorize("hasAuthority('ORDER_MANAGE')")
    public Result<List<OrderShipmentResponseDto>> adminShipments(@PathVariable Long id) {
        return Result.success(orderService.findShipmentsAsAdmin(id));
    }

    @PostMapping("/receive/{id}")
    @PreAuthorize("hasAuthority('ORDER_READ_OWN') and @orderOwnership.isOwner(#id, authentication)")
    public Result<OrderResponseDto> receiveOrder(
            @PathVariable Long id, @AuthenticationPrincipal SessionUser currentUser) {
        return Result.success(orderApplicationService.receiveOrder(currentUser, id));
    }

    @PostMapping("/shipments/receive/{id}")
    @PreAuthorize("hasAuthority('ORDER_READ_OWN')")
    public Result<OrderShipmentResponseDto> receiveShipment(
            @PathVariable Long id, @AuthenticationPrincipal SessionUser currentUser) {
        return Result.success(orderApplicationService.receiveShipment(currentUser, id));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('ORDER_READ_OWN') and @orderOwnership.isOwner(#id, authentication)")
    public Result<Void> hideOrder(@PathVariable Long id, @AuthenticationPrincipal SessionUser currentUser) {
        orderApplicationService.hideOrder(currentUser, id);
        return Result.success();
    }

    @PostMapping("/return/apply/{id}")
    @PreAuthorize("hasAuthority('ORDER_RETURN_REQUEST') and @orderOwnership.isOwner(#id, authentication)")
    public Result<OrderResponseDto> applyReturn(
            @PathVariable Long id, @AuthenticationPrincipal SessionUser currentUser) {
        return Result.success(orderApplicationService.applyReturn(currentUser, id));
    }

    @PostMapping("/return/approve/{id}")
    @PreAuthorize("hasAuthority('ORDER_MANAGE')")
    public Result<OrderResponseDto> approveReturn(@PathVariable Long id) {
        return Result.success(orderService.approveReturn(id));
    }

    @PostMapping("/return/ship/{id}")
    @PreAuthorize("hasAuthority('ORDER_RETURN_REQUEST') and @orderOwnership.isOwner(#id, authentication)")
    public Result<OrderResponseDto> userShipReturn(
            @PathVariable Long id, @AuthenticationPrincipal SessionUser currentUser) {
        return Result.success(orderApplicationService.shipReturn(currentUser, id));
    }

    @PostMapping("/return/confirm/{id}")
    @PreAuthorize("hasAuthority('ORDER_MANAGE')")
    public Result<OrderResponseDto> confirmReturn(@PathVariable Long id) {
        return Result.success(orderService.confirmReturn(id));
    }

    @PostMapping("/review/{id}")
    @PreAuthorize("hasAuthority('ORDER_READ_OWN') and @orderOwnership.isOwner(#id, authentication)")
    public Result<OrderReviewResponseDto> reviewOrder(
            @PathVariable Long id,
            @Valid @RequestBody OrderReviewRequestDto request,
            @AuthenticationPrincipal SessionUser currentUser) {
        return Result.success(orderApplicationService.reviewOrder(currentUser, id, request));
    }

    @GetMapping("/review/{id}")
    @PreAuthorize("hasAuthority('ORDER_READ_OWN') and @orderOwnership.isOwner(#id, authentication)")
    public Result<List<OrderReviewResponseDto>> reviews(
            @PathVariable Long id, @AuthenticationPrincipal SessionUser currentUser) {
        return Result.success(orderApplicationService.findReviews(currentUser, id));
    }

    private static OrderPageQuery toOrderPageQuery(Pageable pageable) {
        List<SortOrder> sortOrders = pageable.getSort().stream()
                .map(order -> new SortOrder(order.getProperty(), order.isAscending() ? Direction.ASC : Direction.DESC))
                .toList();
        return new OrderPageQuery(pageable.getPageNumber(), pageable.getPageSize(), sortOrders);
    }
}
