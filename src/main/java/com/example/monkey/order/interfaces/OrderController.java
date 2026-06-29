package com.example.monkey.order.interfaces;

import com.example.monkey.order.application.OrderApplicationService;
import com.example.monkey.order.application.OrderService;
import com.example.monkey.order.application.dto.OrderPageQuery;
import com.example.monkey.order.application.dto.OrderPageQuery.SortOrder;
import com.example.monkey.order.application.dto.OrderPageQuery.SortOrder.Direction;
import com.example.monkey.order.application.dto.OrderResponseDto;
import com.example.monkey.order.interfaces.dto.CreateOrderRequestDto;
import com.example.monkey.shared.application.dto.PageResponseDto;
import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.interfaces.dto.Result;
import jakarta.validation.Valid;
import java.util.List;
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

    public OrderController(OrderApplicationService orderApplicationService, OrderService orderService) {
        this.orderApplicationService = orderApplicationService;
        this.orderService = orderService;
    }

    @PostMapping("/create")
    @PreAuthorize("hasAuthority('ORDER_CREATE')")
    public Result<OrderResponseDto> createOrder(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateOrderRequestDto requestBody,
            @AuthenticationPrincipal SessionUser currentUser) {
        return Result.success(orderApplicationService.createOrder(
                currentUser, requestBody.monkeyId(), requestBody.addressId(), idempotencyKey));
    }

    @GetMapping("/my")
    @PreAuthorize("hasAuthority('ORDER_READ_OWN')")
    public Result<List<OrderResponseDto>> myOrders(@AuthenticationPrincipal SessionUser currentUser) {
        return Result.success(orderApplicationService.findOrders(currentUser));
    }

    @GetMapping(
            value = "/my",
            params = {"page", "size"})
    @PreAuthorize("hasAuthority('ORDER_READ_OWN')")
    public Result<PageResponseDto<OrderResponseDto>> myOrders(
            @PageableDefault(size = 20, sort = "createTime", direction = Sort.Direction.DESC) Pageable pageable,
            @AuthenticationPrincipal SessionUser currentUser) {
        return Result.success(orderApplicationService.findOrders(currentUser, toOrderPageQuery(pageable)));
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
        return Result.success(orderService.findAllOrders(toOrderPageQuery(pageable)));
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
        return Result.success(orderApplicationService.receiveOrder(currentUser, id));
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

    private static OrderPageQuery toOrderPageQuery(Pageable pageable) {
        List<SortOrder> sortOrders = pageable.getSort().stream()
                .map(order -> new SortOrder(order.getProperty(), order.isAscending() ? Direction.ASC : Direction.DESC))
                .toList();
        return new OrderPageQuery(pageable.getPageNumber(), pageable.getPageSize(), sortOrders);
    }
}
