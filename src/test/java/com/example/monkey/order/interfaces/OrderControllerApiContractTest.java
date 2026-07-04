package com.example.monkey.order.interfaces;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.monkey.order.application.OrderApplicationService;
import com.example.monkey.order.application.OrderService;
import com.example.monkey.order.application.dto.OrderPageQuery;
import com.example.monkey.order.application.dto.OrderResponseDto;
import com.example.monkey.order.domain.OrderStatus;
import com.example.monkey.risk.application.RiskApplicationService;
import com.example.monkey.shared.application.dto.PageResponseDto;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.shared.interfaces.web.GlobalExceptionHandler;
import com.example.monkey.shared.interfaces.web.TraceIdFilter;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class OrderControllerApiContractTest {

    @Mock
    private OrderApplicationService orderApplicationService;

    @Mock
    private OrderService orderService;

    @Mock
    private RiskApplicationService riskApplicationService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        OrderController controller = new OrderController(orderApplicationService, orderService, riskApplicationService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .addFilters(new TraceIdFilter())
                .build();
    }

    @Test
    void successfulOrderReadUsesResultEnvelopeWithTraceId() throws Exception {
        when(orderService.findAllOrders()).thenReturn(List.of(response()));

        mockMvc.perform(get("/api/orders/all").header("X-Trace-Id", "trace-contract-1"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Trace-Id", "trace-contract-1"))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.message").value("ok"))
                .andExpect(jsonPath("$.traceId").value("trace-contract-1"))
                .andExpect(jsonPath("$.data[0].status").value(OrderStatus.PAID.label()));
    }

    @Test
    void versionedOrderReadUsesSameContractEnvelope() throws Exception {
        when(orderService.findAllOrders()).thenReturn(List.of(response()));

        mockMvc.perform(get("/api/v1/orders/all").header("X-Trace-Id", "trace-contract-v1"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Trace-Id", "trace-contract-v1"))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.message").value("ok"))
                .andExpect(jsonPath("$.traceId").value("trace-contract-v1"))
                .andExpect(jsonPath("$.data[0].status").value(OrderStatus.PAID.label()));
    }

    @Test
    void versionedOrderReadSupportsPageableEnvelope() throws Exception {
        when(orderService.findAllOrders(any(OrderPageQuery.class)))
                .thenReturn(new PageResponseDto<>(List.of(response()), 0, 1, 3, 3, true, false));

        mockMvc.perform(get("/api/v1/orders/all")
                        .param("page", "0")
                        .param("size", "1")
                        .header("X-Trace-Id", "trace-page-v1"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Trace-Id", "trace-page-v1"))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.traceId").value("trace-page-v1"))
                .andExpect(jsonPath("$.data.content[0].status").value(OrderStatus.PAID.label()))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(1))
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.totalPages").value(3))
                .andExpect(jsonPath("$.data.first").value(true))
                .andExpect(jsonPath("$.data.last").value(false));
    }

    @Test
    void businessFailureUsesProblemDetailWithErrorCodeAndTraceId() throws Exception {
        when(orderService.shipOrder(11L))
                .thenThrow(new BusinessException(ErrorCode.CONFLICT, "Order status does not allow this operation"));

        mockMvc.perform(post("/api/orders/ship/11").header("X-Trace-Id", "trace-contract-2"))
                .andExpect(status().isConflict())
                .andExpect(header().string("X-Trace-Id", "trace-contract-2"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value("Order status does not allow this operation"))
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.traceId").value("trace-contract-2"));
    }

    @Test
    void invalidRequestBodyUsesValidationProblemDetailBeforeServiceInvocation() throws Exception {
        mockMvc.perform(post("/api/orders/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"monkeyId\":0,\"addressId\":null}")
                        .header("X-Trace-Id", "trace-validation-1"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(header().string("X-Trace-Id", "trace-validation-1"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.detail")
                        .value(org.hamcrest.Matchers.anyOf(
                                org.hamcrest.Matchers.equalTo("monkeyId: monkey id must be positive"),
                                org.hamcrest.Matchers.equalTo("addressId: address id is required"))))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.traceId").value("trace-validation-1"));

        verifyNoInteractions(riskApplicationService, orderApplicationService, orderService);
    }

    private static OrderResponseDto response() {
        return new OrderResponseDto(
                11L,
                "ORD202606280001",
                42L,
                "buyer",
                "/images/avatar/buyer.png",
                7L,
                "Momo",
                "/images/product/momo.png",
                BigDecimal.valueOf(199.99),
                "calm",
                "Ada",
                "13800138000",
                "Hangzhou",
                LocalDateTime.of(2026, 6, 28, 15, 0),
                OrderStatus.PAID.label(),
                LocalDateTime.of(2026, 6, 28, 14, 0));
    }
}
