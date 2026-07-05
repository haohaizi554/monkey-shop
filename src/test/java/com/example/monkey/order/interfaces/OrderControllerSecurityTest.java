package com.example.monkey.order.interfaces;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.monkey.order.application.OrderApplicationService;
import com.example.monkey.order.application.OrderOwnershipService;
import com.example.monkey.order.application.OrderService;
import com.example.monkey.risk.application.RiskApplicationService;
import com.example.monkey.shared.application.observability.AuditService;
import com.example.monkey.shared.application.security.ApiRateLimitApplicationService;
import com.example.monkey.shared.application.security.ApiRateLimitResult;
import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.shared.infrastructure.config.SecurityConfig;
import com.example.monkey.shared.interfaces.web.VisitInterceptor;
import com.example.monkey.user.domain.UserAccountStore;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(controllers = OrderController.class)
@Import({SecurityConfig.class, OrderOwnership.class})
@MockitoBean(
        types = {
            RiskApplicationService.class,
            UserAccountStore.class,
            VisitInterceptor.class,
            AuditService.class,
            OrderOwnershipService.class,
            OrderApplicationService.class,
            OrderService.class,
            ApiRateLimitApplicationService.class
        })
class OrderControllerSecurityTest {

    private final MockMvc mockMvc;
    private final OrderOwnershipService orderOwnershipService;
    private final OrderApplicationService orderApplicationService;
    private final OrderService orderService;
    private final ApiRateLimitApplicationService apiRateLimitService;

    @Autowired
    OrderControllerSecurityTest(
            MockMvc mockMvc,
            OrderOwnershipService orderOwnershipService,
            OrderApplicationService orderApplicationService,
            OrderService orderService,
            ApiRateLimitApplicationService apiRateLimitService) {
        this.mockMvc = mockMvc;
        this.orderOwnershipService = orderOwnershipService;
        this.orderApplicationService = orderApplicationService;
        this.orderService = orderService;
        this.apiRateLimitService = apiRateLimitService;
    }

    @BeforeEach
    void allowRateLimitedTraffic() {
        when(apiRateLimitService.consume(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(new ApiRateLimitResult(true, 0));
    }

    @ParameterizedTest
    @MethodSource("ownedOrderRoutes")
    void userOrderRoutesRejectNonOwners(String path) throws Exception {
        when(orderOwnershipService.isVisibleOwner(42L, 7L)).thenReturn(false);

        mockMvc.perform(post(path).with(csrf()).with(authenticatedUser(7L, "USER")))
                .andExpect(status().isForbidden())
                .andExpect(forbiddenProblem(path));

        verifyNoInteractions(orderApplicationService, orderService);
    }

    @ParameterizedTest
    @MethodSource("ownedOrderRoutes")
    void userOrderRoutesAllowOwners(String path) throws Exception {
        when(orderOwnershipService.isVisibleOwner(42L, 7L)).thenReturn(true);

        mockMvc.perform(post(path).with(csrf()).with(authenticatedUser(7L, "USER")))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @MethodSource("ownedOrderRoutes")
    void adminCannotUseUserOwnedOrderRoutes(String path) throws Exception {
        mockMvc.perform(post(path).with(csrf()).with(authenticatedUser(1L, "ADMIN")))
                .andExpect(status().isForbidden())
                .andExpect(forbiddenProblem(path));

        verifyNoInteractions(orderApplicationService, orderService);
    }

    @Test
    void userHideOrderRejectsNonOwners() throws Exception {
        when(orderOwnershipService.isVisibleOwner(42L, 7L)).thenReturn(false);

        mockMvc.perform(delete("/api/orders/42").with(csrf()).with(authenticatedUser(7L, "USER")))
                .andExpect(status().isForbidden())
                .andExpect(forbiddenProblem("/api/orders/42"));

        verifyNoInteractions(orderApplicationService, orderService);
    }

    @Test
    void userHideOrderAllowsOwners() throws Exception {
        when(orderOwnershipService.isVisibleOwner(42L, 7L)).thenReturn(true);

        mockMvc.perform(delete("/api/orders/42").with(csrf()).with(authenticatedUser(7L, "USER")))
                .andExpect(status().isOk());
    }

    @Test
    void adminCannotHideAnotherUsersOrder() throws Exception {
        mockMvc.perform(delete("/api/orders/42").with(csrf()).with(authenticatedUser(1L, "ADMIN")))
                .andExpect(status().isForbidden())
                .andExpect(forbiddenProblem("/api/orders/42"));

        verifyNoInteractions(orderApplicationService, orderService);
    }

    @ParameterizedTest
    @MethodSource("adminOrderRoutes")
    void adminOrderRoutesRejectUsers(String path) throws Exception {
        mockMvc.perform(post(path).with(csrf()).with(authenticatedUser(7L, "USER")))
                .andExpect(status().isForbidden())
                .andExpect(forbiddenProblem(path));

        verifyNoInteractions(orderApplicationService, orderService);
    }

    @ParameterizedTest
    @MethodSource("adminOrderRoutes")
    void adminOrderRoutesAllowAdmins(String path) throws Exception {
        mockMvc.perform(post(path).with(csrf()).with(authenticatedUser(1L, "ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    void createOrderPropagatesMissingIdempotencyKeyFromApplicationService() throws Exception {
        when(orderApplicationService.createOrder(any(SessionUser.class), eq(7L), eq(3L), eq(null)))
                .thenThrow(new BusinessException(ErrorCode.VALIDATION_ERROR, "Idempotency-Key header is required"));

        mockMvc.perform(post("/api/orders/create")
                        .with(csrf())
                        .with(authenticatedUser(7L, "USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"monkeyId\":7,\"addressId\":3}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.title").value(ErrorCode.VALIDATION_ERROR.defaultMessage()))
                .andExpect(jsonPath("$.detail").value("Idempotency-Key header is required"))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verify(orderApplicationService).createOrder(any(SessionUser.class), eq(7L), eq(3L), eq(null));
    }

    private static Stream<String> ownedOrderRoutes() {
        return Stream.of("/api/orders/receive/42", "/api/orders/return/apply/42", "/api/orders/return/ship/42");
    }

    private static Stream<String> adminOrderRoutes() {
        return Stream.of("/api/orders/ship/42", "/api/orders/return/approve/42", "/api/orders/return/confirm/42");
    }

    private static org.springframework.test.web.servlet.ResultMatcher forbiddenProblem(String path) {
        return result -> {
            content()
                    .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                    .match(result);
            jsonPath("$.status").value(403).match(result);
            jsonPath("$.title").value(ErrorCode.FORBIDDEN.defaultMessage()).match(result);
            jsonPath("$.detail").value(ErrorCode.FORBIDDEN.defaultMessage()).match(result);
            jsonPath("$.instance").value(path).match(result);
            jsonPath("$.code").value("FORBIDDEN").match(result);
            jsonPath("$.traceId").isNotEmpty().match(result);
        };
    }

    private static RequestPostProcessor authenticatedUser(Long id, String role) {
        UsernamePasswordAuthenticationToken token =
                new UsernamePasswordAuthenticationToken(new SessionUser(id, role), null, authorities(role));
        return authentication(token);
    }

    private static List<SimpleGrantedAuthority> authorities(String role) {
        if ("ADMIN".equals(role)) {
            return List.of(
                    new SimpleGrantedAuthority("ROLE_ADMIN"),
                    new SimpleGrantedAuthority("ORDER_MANAGE"),
                    new SimpleGrantedAuthority("ORDER_CREATE"),
                    new SimpleGrantedAuthority("ORDER_READ_OWN"),
                    new SimpleGrantedAuthority("ORDER_RETURN_REQUEST"));
        }
        return List.of(
                new SimpleGrantedAuthority("ROLE_USER"),
                new SimpleGrantedAuthority("ORDER_CREATE"),
                new SimpleGrantedAuthority("ORDER_READ_OWN"),
                new SimpleGrantedAuthority("ORDER_RETURN_REQUEST"));
    }
}
