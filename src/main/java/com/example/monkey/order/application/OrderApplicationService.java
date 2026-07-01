package com.example.monkey.order.application;

import static com.example.monkey.shared.application.security.AuthenticatedPrincipals.requireUserId;

import com.example.monkey.order.application.dto.OrderPageQuery;
import com.example.monkey.order.application.dto.OrderResponseDto;
import com.example.monkey.shared.application.dto.PageResponseDto;
import com.example.monkey.shared.application.security.SessionUser;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class OrderApplicationService {

    private final OrderService orderService;

    public OrderApplicationService(OrderService orderService) {
        this.orderService = orderService;
    }

    public OrderResponseDto createOrder(SessionUser currentUser, Long monkeyId, Long addressId, String idempotencyKey) {
        return orderService.createOrder(requireUserId(currentUser), monkeyId, addressId, idempotencyKey);
    }

    public List<OrderResponseDto> findOrders(SessionUser currentUser) {
        return orderService.findOrdersForUser(requireUserId(currentUser));
    }

    public PageResponseDto<OrderResponseDto> findOrders(SessionUser currentUser, OrderPageQuery pageQuery) {
        return orderService.findOrdersForUser(requireUserId(currentUser), pageQuery);
    }

    public OrderResponseDto receiveOrder(SessionUser currentUser, Long orderId) {
        return orderService.receiveOrder(orderId, requireUserId(currentUser));
    }

    public void hideOrder(SessionUser currentUser, Long orderId) {
        orderService.hideOrderForUser(orderId, requireUserId(currentUser));
    }

    public OrderResponseDto applyReturn(SessionUser currentUser, Long orderId) {
        return orderService.applyReturn(orderId, requireUserId(currentUser));
    }

    public OrderResponseDto shipReturn(SessionUser currentUser, Long orderId) {
        return orderService.shipReturn(orderId, requireUserId(currentUser));
    }
}
