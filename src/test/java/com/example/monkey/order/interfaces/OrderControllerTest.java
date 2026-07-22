package com.example.monkey.order.interfaces;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.order.application.OrderApplicationService;
import com.example.monkey.order.application.OrderService;
import com.example.monkey.order.application.dto.OrderPageQuery;
import com.example.monkey.order.application.dto.OrderPageQuery.SortOrder;
import com.example.monkey.order.application.dto.OrderPageQuery.SortOrder.Direction;
import com.example.monkey.order.application.dto.OrderResponseDto;
import com.example.monkey.order.application.dto.OrderShipmentResponseDto;
import com.example.monkey.order.interfaces.dto.CreateOrderRequestDto;
import com.example.monkey.risk.application.RiskApplicationService;
import com.example.monkey.shared.application.dto.PageResponseDto;
import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.shared.interfaces.dto.Result;
import com.example.monkey.user.domain.UserRoles;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.mock.web.MockHttpServletRequest;

@ExtendWith(MockitoExtension.class)
class OrderControllerTest {

    @Mock
    private OrderApplicationService orderApplicationService;

    @Mock
    private OrderService orderService;

    @Mock
    private RiskApplicationService riskApplicationService;

    private OrderController controller;

    @BeforeEach
    void setUp() {
        controller = new OrderController(orderApplicationService, orderService, riskApplicationService);
    }

    @Test
    void myOrdersDelegatesCurrentUserScopeWithDefaultPage() {
        SessionUser currentUser = user(7L);
        OrderResponseDto order = response();
        PageRequest pageable = PageRequest.of(0, 20, Sort.by(Sort.Order.desc("createTime")));
        PageResponseDto<OrderResponseDto> page = new PageResponseDto<>(List.of(order), 0, 20, 1, 1, true, true);
        when(orderApplicationService.findOrders(eq(currentUser), any(OrderPageQuery.class)))
                .thenReturn(page);

        Result<PageResponseDto<OrderResponseDto>> result = controller.myOrders(pageable, currentUser);

        assertThat(result.code()).isEqualTo("OK");
        assertThat(result.data()).isSameAs(page);
        OrderPageQuery pageRequest = captureUserPageRequest(currentUser);
        assertThat(pageRequest.page()).isZero();
        assertThat(pageRequest.size()).isEqualTo(20);
    }

    @Test
    void myOrdersPageDelegatesCurrentUserScopeAndPageQuery() {
        SessionUser currentUser = user(7L);
        OrderResponseDto order = response();
        PageRequest pageable = PageRequest.of(2, 25, Sort.by(Sort.Order.asc("id")));
        PageResponseDto<OrderResponseDto> page = new PageResponseDto<>(List.of(order), 2, 25, 1, 3, false, true);
        when(orderApplicationService.findOrders(eq(currentUser), any(OrderPageQuery.class)))
                .thenReturn(page);

        Result<PageResponseDto<OrderResponseDto>> result = controller.myOrders(pageable, currentUser);

        assertThat(result.code()).isEqualTo("OK");
        assertThat(result.data()).isSameAs(page);
        OrderPageQuery pageRequest = captureUserPageRequest(currentUser);
        assertThat(pageRequest.page()).isEqualTo(2);
        assertThat(pageRequest.size()).isEqualTo(25);
        assertThat(pageRequest.sortOrders()).containsExactly(new SortOrder("id", Direction.ASC));
    }

    @Test
    void myOrdersPropagatesMissingAuthenticatedUserFromApplicationService() {
        doThrow(new BusinessException(ErrorCode.UNAUTHORIZED, "login required"))
                .when(orderApplicationService)
                .findOrders(eq(null), any(OrderPageQuery.class));

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> controller.myOrders(PageRequest.of(0, 20), null))
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));

        verify(orderApplicationService).findOrders(eq(null), any(OrderPageQuery.class));
    }

    @Test
    void allOrdersDelegatesToServiceWithDefaultPage() {
        OrderResponseDto order = response();
        PageRequest pageable = PageRequest.of(0, 20, Sort.by(Sort.Order.desc("createTime")));
        PageResponseDto<OrderResponseDto> page = new PageResponseDto<>(List.of(order), 0, 20, 1, 1, true, true);
        when(orderService.findAllOrders(any(OrderPageQuery.class))).thenReturn(page);

        Result<PageResponseDto<OrderResponseDto>> result = controller.getAllOrders(pageable);

        assertThat(result.code()).isEqualTo("OK");
        assertThat(result.data()).isSameAs(page);
        assertThat(captureAllPageRequest().size()).isEqualTo(20);
    }

    @Test
    void allOrdersPageDelegatesToService() {
        OrderResponseDto order = response();
        PageRequest pageable = PageRequest.of(1, 25, Sort.by(Sort.Order.asc("id")));
        PageResponseDto<OrderResponseDto> page = new PageResponseDto<>(List.of(order), 0, 20, 1, 1, true, true);
        when(orderService.findAllOrders(any(OrderPageQuery.class))).thenReturn(page);

        Result<PageResponseDto<OrderResponseDto>> result = controller.getAllOrders(pageable);

        assertThat(result.code()).isEqualTo("OK");
        assertThat(result.data()).isSameAs(page);
        OrderPageQuery pageRequest = captureAllPageRequest();
        assertThat(pageRequest.page()).isEqualTo(1);
        assertThat(pageRequest.size()).isEqualTo(25);
        assertThat(pageRequest.sortOrders()).containsExactly(new SortOrder("id", Direction.ASC));
    }

    @Test
    void createOrderReturnsResultEnvelope() {
        SessionUser currentUser = user(7L);
        OrderResponseDto order = response();
        when(orderApplicationService.createOrder(currentUser, 3L, 5L, "order-key-1"))
                .thenReturn(order);

        var result = controller.createOrder(
                "order-key-1", "device-a", new CreateOrderRequestDto(3L, 5L), currentUser, request());

        assertThat(result.code()).isEqualTo("OK");
        assertThat(result.data()).isSameAs(order);
        verify(orderApplicationService).createOrder(currentUser, 3L, 5L, "order-key-1");
    }

    @Test
    void createOrderPropagatesMissingIdempotencyKeyFromApplicationService() {
        SessionUser currentUser = user(7L);
        when(orderApplicationService.createOrder(currentUser, 3L, 5L, " "))
                .thenThrow(new BusinessException(ErrorCode.VALIDATION_ERROR, "Idempotency-Key header is required"));

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> controller.createOrder(
                        " ", "device-a", new CreateOrderRequestDto(3L, 5L), currentUser, request()))
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));

        verify(orderApplicationService).createOrder(currentUser, 3L, 5L, " ");
    }

    @Test
    void receiveOrderDelegatesCurrentUserScope() {
        SessionUser currentUser = user(7L);
        OrderResponseDto order = response();
        when(orderApplicationService.receiveOrder(currentUser, 11L)).thenReturn(order);

        Result<OrderResponseDto> result = controller.receiveOrder(11L, currentUser);

        assertThat(result.code()).isEqualTo("OK");
        assertThat(result.data()).isSameAs(order);
        verify(orderApplicationService).receiveOrder(currentUser, 11L);
    }

    @Test
    void hideOrderDelegatesCurrentUserScope() {
        SessionUser currentUser = user(7L);

        Result<Void> result = controller.hideOrder(11L, currentUser);

        assertThat(result.code()).isEqualTo("OK");
        verify(orderApplicationService).hideOrder(currentUser, 11L);
    }

    @Test
    void applyReturnDelegatesCurrentUserScope() {
        SessionUser currentUser = user(7L);
        OrderResponseDto order = response();
        when(orderApplicationService.applyReturn(currentUser, 11L)).thenReturn(order);

        Result<OrderResponseDto> result = controller.applyReturn(11L, currentUser);

        assertThat(result.data()).isSameAs(order);
        verify(orderApplicationService).applyReturn(currentUser, 11L);
    }

    @Test
    void userShipReturnDelegatesCurrentUserScope() {
        SessionUser currentUser = user(7L);
        OrderResponseDto order = response();
        when(orderApplicationService.shipReturn(currentUser, 11L)).thenReturn(order);

        Result<OrderResponseDto> result = controller.userShipReturn(11L, currentUser);

        assertThat(result.data()).isSameAs(order);
        verify(orderApplicationService).shipReturn(currentUser, 11L);
    }

    @Test
    void adminShipmentsDelegateToManagementServiceWithoutCustomerOwnership() {
        OrderShipmentResponseDto shipment = new OrderShipmentResponseDto(
                501L, 11L, "SHP501", "SF", "SF501", "SHIPPED", LocalDateTime.of(2026, 6, 28, 16, 0), null, List.of());
        when(orderService.findShipmentsAsAdmin(11L)).thenReturn(List.of(shipment));

        Result<List<OrderShipmentResponseDto>> result = controller.adminShipments(11L);

        assertThat(result.data()).containsExactly(shipment);
        verify(orderService).findShipmentsAsAdmin(11L);
    }

    private static OrderResponseDto response() {
        return new OrderResponseDto(
                11L,
                "ORD202606280001",
                7L,
                "buyer",
                "/images/avatar/buyer.png",
                3L,
                "Momo",
                "/images/product/momo.png",
                BigDecimal.valueOf(199.99),
                "calm",
                "Ada",
                "13800138000",
                "Hangzhou",
                LocalDateTime.of(2026, 6, 28, 15, 0),
                "paid",
                LocalDateTime.of(2026, 6, 28, 14, 0));
    }

    private OrderPageQuery captureUserPageRequest(SessionUser currentUser) {
        ArgumentCaptor<OrderPageQuery> captor = ArgumentCaptor.forClass(OrderPageQuery.class);
        verify(orderApplicationService).findOrders(eq(currentUser), captor.capture());
        return captor.getValue();
    }

    private OrderPageQuery captureAllPageRequest() {
        ArgumentCaptor<OrderPageQuery> captor = ArgumentCaptor.forClass(OrderPageQuery.class);
        verify(orderService).findAllOrders(captor.capture());
        return captor.getValue();
    }

    private static SessionUser user(Long id) {
        return new SessionUser(id, UserRoles.USER);
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.7");
        return request;
    }
}
