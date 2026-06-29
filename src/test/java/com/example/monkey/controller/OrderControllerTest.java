package com.example.monkey.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.monkey.domain.order.OrderStore.OrderPageRequest;
import com.example.monkey.domain.order.OrderStore.SortOrder;
import com.example.monkey.domain.order.OrderStore.SortOrder.Direction;
import com.example.monkey.domain.user.SessionUser;
import com.example.monkey.domain.user.UserRoles;
import com.example.monkey.dto.OrderResponseDto;
import com.example.monkey.dto.PageResponseDto;
import com.example.monkey.service.OrderService;
import com.example.monkey.shared.api.ErrorCode;
import com.example.monkey.shared.api.Result;
import com.example.monkey.shared.exception.BusinessException;
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

@ExtendWith(MockitoExtension.class)
class OrderControllerTest {

    @Mock
    private OrderService orderService;

    private OrderController controller;

    @BeforeEach
    void setUp() {
        controller = new OrderController(orderService);
    }

    @Test
    void myOrdersUsesCurrentUserScope() {
        OrderResponseDto order = response();
        when(orderService.findOrdersForUser(7L)).thenReturn(List.of(order));

        Result<List<OrderResponseDto>> result = controller.myOrders(user(7L));

        assertThat(result.code()).isEqualTo("OK");
        assertThat(result.data()).containsExactly(order);
        verify(orderService).findOrdersForUser(7L);
    }

    @Test
    void myOrdersPageUsesCurrentUserScope() {
        OrderResponseDto order = response();
        PageRequest pageable = PageRequest.of(0, 20, Sort.by(Sort.Order.desc("createTime")));
        PageResponseDto<OrderResponseDto> page = new PageResponseDto<>(List.of(order), 0, 20, 1, 1, true, true);
        when(orderService.findOrdersForUser(eq(7L), any(OrderPageRequest.class)))
                .thenReturn(page);

        Result<PageResponseDto<OrderResponseDto>> result = controller.myOrders(pageable, user(7L));

        assertThat(result.code()).isEqualTo("OK");
        assertThat(result.data()).isSameAs(page);
        OrderPageRequest pageRequest = captureUserPageRequest();
        assertThat(pageRequest.page()).isZero();
        assertThat(pageRequest.size()).isEqualTo(20);
        assertThat(pageRequest.sortOrders()).containsExactly(new SortOrder("createTime", Direction.DESC));
    }

    @Test
    void myOrdersRejectsMissingAuthenticatedUser() {
        assertThatThrownBy(() -> controller.myOrders(null))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));

        verifyNoInteractions(orderService);
    }

    @Test
    void allOrdersDelegatesToService() {
        OrderResponseDto order = response();
        when(orderService.findAllOrders()).thenReturn(List.of(order));

        Result<List<OrderResponseDto>> result = controller.getAllOrders();

        assertThat(result.code()).isEqualTo("OK");
        assertThat(result.data()).containsExactly(order);
        verify(orderService).findAllOrders();
    }

    @Test
    void allOrdersPageDelegatesToService() {
        OrderResponseDto order = response();
        PageRequest pageable = PageRequest.of(1, 25, Sort.by(Sort.Order.asc("id")));
        PageResponseDto<OrderResponseDto> page = new PageResponseDto<>(List.of(order), 0, 20, 1, 1, true, true);
        when(orderService.findAllOrders(any(OrderPageRequest.class))).thenReturn(page);

        Result<PageResponseDto<OrderResponseDto>> result = controller.getAllOrders(pageable);

        assertThat(result.code()).isEqualTo("OK");
        assertThat(result.data()).isSameAs(page);
        OrderPageRequest pageRequest = captureAllPageRequest();
        assertThat(pageRequest.page()).isEqualTo(1);
        assertThat(pageRequest.size()).isEqualTo(25);
        assertThat(pageRequest.sortOrders()).containsExactly(new SortOrder("id", Direction.ASC));
    }

    @Test
    void createOrderReturnsResultEnvelope() {
        OrderResponseDto order = response();
        when(orderService.createOrder(7L, 3L, 5L, "order-key-1")).thenReturn(order);

        var result = controller.createOrder(
                "order-key-1", new com.example.monkey.dto.CreateOrderRequestDto(3L, 5L), user(7L));

        assertThat(result.code()).isEqualTo("OK");
        assertThat(result.data()).isSameAs(order);
        verify(orderService).createOrder(7L, 3L, 5L, "order-key-1");
    }

    @Test
    void createOrderRequiresIdempotencyKeyBeforeServiceInvocation() {
        assertThatThrownBy(() ->
                        controller.createOrder(" ", new com.example.monkey.dto.CreateOrderRequestDto(3L, 5L), user(7L)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));

        verifyNoInteractions(orderService);
    }

    @Test
    void hideOrderUsesCurrentUserScope() {
        Result<Void> result = controller.hideOrder(11L, user(7L));

        assertThat(result.code()).isEqualTo("OK");
        verify(orderService).hideOrderForUser(11L, 7L);
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

    private OrderPageRequest captureUserPageRequest() {
        ArgumentCaptor<OrderPageRequest> captor = ArgumentCaptor.forClass(OrderPageRequest.class);
        verify(orderService).findOrdersForUser(eq(7L), captor.capture());
        return captor.getValue();
    }

    private OrderPageRequest captureAllPageRequest() {
        ArgumentCaptor<OrderPageRequest> captor = ArgumentCaptor.forClass(OrderPageRequest.class);
        verify(orderService).findAllOrders(captor.capture());
        return captor.getValue();
    }

    private static SessionUser user(Long id) {
        return new SessionUser(id, UserRoles.USER);
    }
}
