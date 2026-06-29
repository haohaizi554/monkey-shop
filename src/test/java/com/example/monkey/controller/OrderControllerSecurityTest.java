package com.example.monkey.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.monkey.config.SecurityConfig;
import com.example.monkey.domain.order.OrderOwnershipChecker;
import com.example.monkey.domain.security.ApiRateLimiter;
import com.example.monkey.domain.user.SessionUser;
import com.example.monkey.domain.user.UserAccountStore;
import com.example.monkey.security.OrderOwnership;
import com.example.monkey.service.AuditService;
import com.example.monkey.service.OrderService;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(controllers = OrderController.class)
@Import({SecurityConfig.class, OrderOwnership.class})
class OrderControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OrderOwnershipChecker orderOwnershipChecker;

    @MockBean
    private OrderService orderService;

    @MockBean
    private UserAccountStore userAccountStore;

    @MockBean
    private com.example.monkey.interceptor.VisitInterceptor visitInterceptor;

    @MockBean
    private AuditService auditService;

    @MockBean
    private ApiRateLimiter apiRateLimitService;

    @BeforeEach
    void allowRateLimitedTraffic() {
        when(apiRateLimitService.consume(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(new ApiRateLimiter.RateLimitDecision(true, null, 0));
    }

    @ParameterizedTest
    @MethodSource("ownedOrderRoutes")
    void userOrderRoutesRejectNonOwners(String path) throws Exception {
        when(orderOwnershipChecker.isVisibleOwner(42L, 7L)).thenReturn(false);

        mockMvc.perform(post(path).with(csrf()).with(authenticatedUser(7L, "USER")))
                .andExpect(status().isForbidden())
                .andExpect(forbiddenProblem(path));

        verifyNoInteractions(orderService);
    }

    @ParameterizedTest
    @MethodSource("ownedOrderRoutes")
    void userOrderRoutesAllowOwners(String path) throws Exception {
        when(orderOwnershipChecker.isVisibleOwner(42L, 7L)).thenReturn(true);

        mockMvc.perform(post(path).with(csrf()).with(authenticatedUser(7L, "USER")))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @MethodSource("ownedOrderRoutes")
    void adminCannotUseUserOwnedOrderRoutes(String path) throws Exception {
        mockMvc.perform(post(path).with(csrf()).with(authenticatedUser(1L, "ADMIN")))
                .andExpect(status().isForbidden())
                .andExpect(forbiddenProblem(path));

        verifyNoInteractions(orderService);
    }

    @Test
    void userHideOrderRejectsNonOwners() throws Exception {
        when(orderOwnershipChecker.isVisibleOwner(42L, 7L)).thenReturn(false);

        mockMvc.perform(delete("/api/orders/42").with(csrf()).with(authenticatedUser(7L, "USER")))
                .andExpect(status().isForbidden())
                .andExpect(forbiddenProblem("/api/orders/42"));

        verifyNoInteractions(orderService);
    }

    @Test
    void userHideOrderAllowsOwners() throws Exception {
        when(orderOwnershipChecker.isVisibleOwner(42L, 7L)).thenReturn(true);

        mockMvc.perform(delete("/api/orders/42").with(csrf()).with(authenticatedUser(7L, "USER")))
                .andExpect(status().isOk());
    }

    @Test
    void adminCannotHideAnotherUsersOrder() throws Exception {
        mockMvc.perform(delete("/api/orders/42").with(csrf()).with(authenticatedUser(1L, "ADMIN")))
                .andExpect(status().isForbidden())
                .andExpect(forbiddenProblem("/api/orders/42"));

        verifyNoInteractions(orderService);
    }

    @ParameterizedTest
    @MethodSource("adminOrderRoutes")
    void adminOrderRoutesRejectUsers(String path) throws Exception {
        mockMvc.perform(post(path).with(csrf()).with(authenticatedUser(7L, "USER")))
                .andExpect(status().isForbidden())
                .andExpect(forbiddenProblem(path));

        verifyNoInteractions(orderService);
    }

    @ParameterizedTest
    @MethodSource("adminOrderRoutes")
    void adminOrderRoutesAllowAdmins(String path) throws Exception {
        mockMvc.perform(post(path).with(csrf()).with(authenticatedUser(1L, "ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    void createOrderRequiresIdempotencyKeyBeforeServiceInvocation() throws Exception {
        mockMvc.perform(post("/api/orders/create")
                        .with(csrf())
                        .with(authenticatedUser(7L, "USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"monkeyId\":7,\"addressId\":3}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.detail").value("Idempotency-Key header is required"))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verifyNoInteractions(orderService);
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
            jsonPath("$.title").value("Operation is not permitted").match(result);
            jsonPath("$.detail").value("Operation is not permitted").match(result);
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
