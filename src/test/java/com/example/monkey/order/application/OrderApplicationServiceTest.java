package com.example.monkey.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.monkey.order.application.dto.OrderPageQuery;
import com.example.monkey.order.application.dto.OrderResponseDto;
import com.example.monkey.shared.application.dto.PageResponseDto;
import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.user.domain.UserRoles;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrderApplicationServiceTest {

    @Mock
    private OrderService orderService;

    private OrderApplicationService orderApplicationService;

    @BeforeEach
    void setUp() {
        orderApplicationService = new OrderApplicationService(orderService);
    }

    @Test
    void createOrderRequiresAuthenticatedUserBeforeDelegating() {
        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> orderApplicationService.createOrder(null, 3L, 5L, "order-key-1"))
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));

        verifyNoInteractions(orderService);
    }

    @Test
    void createOrderDelegatesWithRequiredUserId() {
        OrderResponseDto order = response();
        when(orderService.createOrder(7L, 3L, 5L, "order-key-1")).thenReturn(order);

        OrderResponseDto result = orderApplicationService.createOrder(user(), 3L, 5L, "order-key-1");

        assertThat(result).isSameAs(order);
        verify(orderService).createOrder(7L, 3L, 5L, "order-key-1");
    }

    @Test
    void findOrdersDelegatesWithRequiredUserId() {
        OrderResponseDto order = response();
        when(orderService.findOrdersForUser(7L)).thenReturn(List.of(order));

        List<OrderResponseDto> result = orderApplicationService.findOrders(user());

        assertThat(result).containsExactly(order);
        verify(orderService).findOrdersForUser(7L);
    }

    @Test
    void findPagedOrdersDelegatesWithRequiredUserIdAndPageQuery() {
        OrderPageQuery pageQuery = new OrderPageQuery(0, 20, List.of());
        PageResponseDto<OrderResponseDto> page = new PageResponseDto<>(List.of(response()), 0, 20, 1, 1, true, true);
        when(orderService.findOrdersForUser(7L, pageQuery)).thenReturn(page);

        PageResponseDto<OrderResponseDto> result = orderApplicationService.findOrders(user(), pageQuery);

        assertThat(result).isSameAs(page);
        verify(orderService).findOrdersForUser(7L, pageQuery);
    }

    @Test
    void receiveOrderDelegatesWithRequiredUserId() {
        OrderResponseDto order = response();
        when(orderService.receiveOrder(11L, 7L)).thenReturn(order);

        OrderResponseDto result = orderApplicationService.receiveOrder(user(), 11L);

        assertThat(result).isSameAs(order);
        verify(orderService).receiveOrder(11L, 7L);
    }

    @Test
    void hideOrderDelegatesWithRequiredUserId() {
        orderApplicationService.hideOrder(user(), 11L);

        verify(orderService).hideOrderForUser(11L, 7L);
    }

    @Test
    void applyReturnDelegatesWithRequiredUserId() {
        OrderResponseDto order = response();
        when(orderService.applyReturn(11L, 7L)).thenReturn(order);

        OrderResponseDto result = orderApplicationService.applyReturn(user(), 11L);

        assertThat(result).isSameAs(order);
        verify(orderService).applyReturn(11L, 7L);
    }

    @Test
    void shipReturnDelegatesWithRequiredUserId() {
        OrderResponseDto order = response();
        when(orderService.shipReturn(11L, 7L)).thenReturn(order);

        OrderResponseDto result = orderApplicationService.shipReturn(user(), 11L);

        assertThat(result).isSameAs(order);
        verify(orderService).shipReturn(11L, 7L);
    }

    private static SessionUser user() {
        return new SessionUser(7L, UserRoles.USER);
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
}
