package com.example.monkey.order.application;

import static com.example.monkey.shared.application.security.AuthenticatedPrincipals.requireUserId;

import com.example.monkey.order.application.dto.OrderPageQuery;
import com.example.monkey.order.application.dto.OrderResponseDto;
import com.example.monkey.order.application.dto.OrderReviewRequestDto;
import com.example.monkey.order.application.dto.OrderReviewResponseDto;
import com.example.monkey.order.application.dto.OrderShipmentResponseDto;
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

    public OrderResponseDto findOrder(SessionUser currentUser, Long orderId) {
        return orderService.findOrderForUser(orderId, requireUserId(currentUser));
    }

    public OrderResponseDto receiveOrder(SessionUser currentUser, Long orderId) {
        return orderService.receiveOrder(orderId, requireUserId(currentUser));
    }

    public OrderShipmentResponseDto receiveShipment(SessionUser currentUser, Long shipmentId) {
        return orderService.receiveShipment(shipmentId, requireUserId(currentUser));
    }

    public List<OrderShipmentResponseDto> findShipments(SessionUser currentUser, Long orderId) {
        return orderService.findShipments(orderId, requireUserId(currentUser));
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

    public OrderReviewResponseDto reviewOrder(SessionUser currentUser, Long orderId, OrderReviewRequestDto request) {
        return orderService.reviewOrder(orderId, requireUserId(currentUser), request);
    }

    public List<OrderReviewResponseDto> findReviews(SessionUser currentUser, Long orderId) {
        return orderService.findReviews(orderId, requireUserId(currentUser));
    }
}
