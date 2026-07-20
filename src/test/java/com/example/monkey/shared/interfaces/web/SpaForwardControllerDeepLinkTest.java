package com.example.monkey.shared.interfaces.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SpaForwardControllerDeepLinkTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new SpaForwardController()).build();
    }

    @Test
    void forwardsEveryAdminWorkspaceWhenOpenedOrRefreshedDirectly() throws Exception {
        for (String route :
                new String[] {"/admin/orders", "/admin/returns", "/admin/payments", "/admin/logistics", "/admin/members"
                }) {
            mockMvc.perform(get(route)).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
        }
    }

    @Test
    void forwardsEveryConsumerDetailRouteWhenOpenedOrRefreshedDirectly() throws Exception {
        for (String route : new String[] {"/shop/101", "/orders/202/review", "/payment/303", "/logistics/404"}) {
            mockMvc.perform(get(route)).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
        }
    }
}
