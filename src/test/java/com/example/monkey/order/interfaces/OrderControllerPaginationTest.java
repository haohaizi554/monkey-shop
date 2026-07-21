package com.example.monkey.order.interfaces;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.order.application.OrderApplicationService;
import com.example.monkey.order.application.OrderService;
import com.example.monkey.order.application.dto.OrderPageQuery;
import com.example.monkey.risk.application.RiskApplicationService;
import com.example.monkey.shared.application.dto.PageResponseDto;
import com.example.monkey.shared.application.security.SessionUser;
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
class OrderControllerPaginationTest {

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
    void ownOrdersCarryStatusAndKeywordIntoThePagedQuery() {
        PageRequest pageable = PageRequest.of(2, 10, Sort.by(Sort.Order.desc("createTime")));
        SessionUser user = new SessionUser(42L, "USER");
        when(orderApplicationService.findOrders(any(SessionUser.class), any(OrderPageQuery.class)))
                .thenReturn(new PageResponseDto<>(List.of(), 2, 10, 0, 0, false, true));

        controller.myOrders(List.of("PAID"), " Momo ", pageable, user);

        ArgumentCaptor<OrderPageQuery> query = ArgumentCaptor.forClass(OrderPageQuery.class);
        verify(orderApplicationService).findOrders(eq(user), query.capture());
        assertThat(query.getValue().page()).isEqualTo(2);
        assertThat(query.getValue().size()).isEqualTo(10);
        assertThat(query.getValue().statuses()).contains("PAID");
        assertThat(query.getValue().keyword()).isEqualTo("Momo");
    }

    @Test
    void adminOrdersCarryKeywordIntoThePagedQuery() {
        PageRequest pageable = PageRequest.of(0, 25, Sort.by(Sort.Order.asc("orderNo")));
        when(orderService.findAllOrders(any(OrderPageQuery.class)))
                .thenReturn(new PageResponseDto<>(List.of(), 0, 25, 0, 0, true, true));

        controller.getAllOrders(List.of(), " ORD-42 ", pageable);

        ArgumentCaptor<OrderPageQuery> query = ArgumentCaptor.forClass(OrderPageQuery.class);
        verify(orderService).findAllOrders(query.capture());
        assertThat(query.getValue().keyword()).isEqualTo("ORD-42");
        assertThat(query.getValue().sortOrders()).hasSize(1);
    }
}
