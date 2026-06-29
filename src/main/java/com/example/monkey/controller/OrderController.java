package com.example.monkey.controller;

import static com.example.monkey.domain.user.AuthenticatedPrincipals.requireUserId;

import com.example.monkey.domain.order.OrderStore.OrderPageRequest;
import com.example.monkey.domain.order.OrderStore.SortOrder;
import com.example.monkey.domain.order.OrderStore.SortOrder.Direction;
import com.example.monkey.domain.user.SessionUser;
import com.example.monkey.dto.CreateOrderRequestDto;
import com.example.monkey.dto.OrderResponseDto;
import com.example.monkey.dto.PageResponseDto;
import com.example.monkey.service.OrderService;
import com.example.monkey.shared.api.ErrorCode;
import com.example.monkey.shared.api.Result;
import com.example.monkey.shared.exception.BusinessException;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.StringUtils;
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

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping("/create")
    @PreAuthorize("hasAuthority('ORDER_CREATE')")
    public Result<OrderResponseDto> createOrder(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateOrderRequestDto requestBody,
            @AuthenticationPrincipal SessionUser currentUser) {
        String requiredIdempotencyKey = requireIdempotencyKey(idempotencyKey);
        return Result.success(orderService.createOrder(
                requireUserId(currentUser), requestBody.monkeyId(), requestBody.addressId(), requiredIdempotencyKey));
    }

    @GetMapping("/my")
    @PreAuthorize("hasAuthority('ORDER_READ_OWN')")
    public Result<List<OrderResponseDto>> myOrders(@AuthenticationPrincipal SessionUser currentUser) {
        return Result.success(orderService.findOrdersForUser(requireUserId(currentUser)));
    }

    @GetMapping(
            value = "/my",
            params = {"page", "size"})
    @PreAuthorize("hasAuthority('ORDER_READ_OWN')")
    public Result<PageResponseDto<OrderResponseDto>> myOrders(
            @PageableDefault(size = 20, sort = "createTime", direction = Sort.Direction.DESC) Pageable pageable,
            @AuthenticationPrincipal SessionUser currentUser) {
        return Result.success(orderService.findOrdersForUser(requireUserId(currentUser), toOrderPageRequest(pageable)));
    }

    @GetMapping("/all")
    @PreAuthorize("hasAuthority('ORDER_MANAGE')")
    public Result<List<OrderResponseDto>> getAllOrders() {
        return Result.success(orderService.findAllOrders());
    }

    @GetMapping(
            value = "/all",
            params = {"page", "size"})
    @PreAuthorize("hasAuthority('ORDER_MANAGE')")
    public Result<PageResponseDto<OrderResponseDto>> getAllOrders(
            @PageableDefault(size = 20, sort = "createTime", direction = Sort.Direction.DESC) Pageable pageable) {
        return Result.success(orderService.findAllOrders(toOrderPageRequest(pageable)));
    }

    @PostMapping("/ship/{id}")
    @PreAuthorize("hasAuthority('ORDER_MANAGE')")
    public Result<OrderResponseDto> shipOrder(@PathVariable Long id) {
        return Result.success(orderService.shipOrder(id));
    }

    @PostMapping("/receive/{id}")
    @PreAuthorize("hasAuthority('ORDER_READ_OWN') and @orderOwnership.isOwner(#id, authentication)")
    public Result<OrderResponseDto> receiveOrder(
            @PathVariable Long id, @AuthenticationPrincipal SessionUser currentUser) {
        return Result.success(orderService.receiveOrder(id, requireUserId(currentUser)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('ORDER_READ_OWN') and @orderOwnership.isOwner(#id, authentication)")
    public Result<Void> hideOrder(@PathVariable Long id, @AuthenticationPrincipal SessionUser currentUser) {
        orderService.hideOrderForUser(id, requireUserId(currentUser));
        return Result.success();
    }

    @PostMapping("/return/apply/{id}")
    @PreAuthorize("hasAuthority('ORDER_RETURN_REQUEST') and @orderOwnership.isOwner(#id, authentication)")
    public Result<OrderResponseDto> applyReturn(
            @PathVariable Long id, @AuthenticationPrincipal SessionUser currentUser) {
        return Result.success(orderService.applyReturn(id, requireUserId(currentUser)));
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
        return Result.success(orderService.shipReturn(id, requireUserId(currentUser)));
    }

    @PostMapping("/return/confirm/{id}")
    @PreAuthorize("hasAuthority('ORDER_MANAGE')")
    public Result<OrderResponseDto> confirmReturn(@PathVariable Long id) {
        return Result.success(orderService.confirmReturn(id));
    }

    private static String requireIdempotencyKey(String idempotencyKey) {
        if (!StringUtils.hasText(idempotencyKey)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Idempotency-Key header is required");
        }
        return idempotencyKey;
    }

    private static OrderPageRequest toOrderPageRequest(Pageable pageable) {
        List<SortOrder> sortOrders = pageable.getSort().stream()
                .map(order -> new SortOrder(order.getProperty(), order.isAscending() ? Direction.ASC : Direction.DESC))
                .toList();
        return new OrderPageRequest(pageable.getPageNumber(), pageable.getPageSize(), sortOrders);
    }
}
