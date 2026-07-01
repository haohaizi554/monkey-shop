package com.example.monkey.shared.interfaces.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SpaForwardControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new SpaForwardController()).build();
    }

    @Test
    void forwardsHistoryModeSpaRoutesToPackagedIndex() throws Exception {
        for (String route : new String[] {"/", "/login", "/shop", "/orders", "/profile", "/admin"}) {
            mockMvc.perform(get(route)).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
        }
    }

    @Test
    void redirectsLegacyHtmlEntrypointsToSpaRoutes() throws Exception {
        Map<String, String> redirects = Map.of(
                "/index.html", "/shop",
                "/shop.html", "/shop",
                "/orders.html", "/orders",
                "/profile.html", "/profile",
                "/admin.html", "/admin");

        for (Map.Entry<String, String> redirect : redirects.entrySet()) {
            mockMvc.perform(get(redirect.getKey()))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl(redirect.getValue()));
        }
    }
}
