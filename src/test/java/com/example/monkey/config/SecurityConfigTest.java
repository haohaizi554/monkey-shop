package com.example.monkey.config;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.monkey.security.SessionIdentity;
import com.example.monkey.security.SessionUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(controllers = SecurityConfigTest.TestApiController.class)
@Import({SecurityConfig.class, SecurityConfigTest.TestApiController.class})
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private com.example.monkey.interceptor.VisitInterceptor visitInterceptor;

    @Test
    void publicApiResponseIncludesSecurityHeaders() throws Exception {
        mockMvc.perform(get("/api/monkeys").secure(true))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "Content-Security-Policy",
                        org.hamcrest.Matchers.containsString("default-src 'self'")))
                .andExpect(header().string(
                        "Content-Security-Policy",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("unpkg.com"))))
                .andExpect(header().string(
                        "Content-Security-Policy",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("script-src 'self' 'unsafe-inline'"))))
                .andExpect(header().string(
                        "Content-Security-Policy",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("style-src 'self' 'unsafe-inline'"))))
                .andExpect(header().string(
                        "Strict-Transport-Security",
                        org.hamcrest.Matchers.containsString("max-age=31536000")))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
                .andExpect(header().string("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()"));
    }

    @Test
    void unsafeAdminEndpointRejectsMissingCsrfToken() throws Exception {
        mockMvc.perform(post("/api/monkeys/add").sessionAttr("IDENTITY", "ADMIN"))
                .andExpect(status().isForbidden());
    }

    @Test
    void unsafeAdminEndpointRequiresAdminSessionRole() throws Exception {
        mockMvc.perform(post("/api/monkeys/add")
                        .with(csrf())
                        .sessionAttr(SessionIdentity.USER_ID_ATTRIBUTE, 7L)
                        .sessionAttr(SessionIdentity.IDENTITY_ATTRIBUTE, SessionIdentity.ROLE_USER))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/monkeys/add")
                        .with(csrf())
                        .sessionAttr(SessionIdentity.USER_ID_ATTRIBUTE, 1L)
                        .sessionAttr(SessionIdentity.IDENTITY_ATTRIBUTE, SessionIdentity.ROLE_ADMIN))
                .andExpect(status().isOk());
    }

    @Test
    void uploadEndpointRequiresAuthenticatedSessionRole() throws Exception {
        mockMvc.perform(post("/api/upload").with(csrf()))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/upload")
                        .with(csrf())
                        .sessionAttr(SessionIdentity.USER_ID_ATTRIBUTE, 7L)
                        .sessionAttr(SessionIdentity.IDENTITY_ATTRIBUTE, SessionIdentity.ROLE_USER))
                .andExpect(status().isOk());
    }

    @Test
    void unknownApiRoutesAreDeniedByDefault() throws Exception {
        mockMvc.perform(get("/api/unknown")
                        .sessionAttr(SessionIdentity.USER_ID_ATTRIBUTE, 1L)
                        .sessionAttr(SessionIdentity.IDENTITY_ATTRIBUTE, SessionIdentity.ROLE_ADMIN))
                .andExpect(status().isForbidden());
    }

    @Test
    void sessionIdentityIsExposedAsAuthenticationPrincipal() throws Exception {
        mockMvc.perform(get("/api/user/me")
                        .sessionAttr(SessionIdentity.USER_ID_ATTRIBUTE, 7L)
                        .sessionAttr(SessionIdentity.IDENTITY_ATTRIBUTE, SessionIdentity.ROLE_USER))
                .andExpect(status().isOk())
                .andExpect(content().string("7:USER"));
    }

    @RestController
    public static class TestApiController {

        @GetMapping("/api/monkeys")
        String monkeys() {
            return "ok";
        }

        @PostMapping("/api/monkeys/add")
        String addMonkey() {
            return "ok";
        }

        @PostMapping("/api/upload")
        String upload() {
            return "ok";
        }

        @GetMapping("/api/user/me")
        String me(@AuthenticationPrincipal SessionUser currentUser) {
            return currentUser == null ? "anonymous" : currentUser.id() + ":" + currentUser.role();
        }

        @GetMapping("/api/unknown")
        String unknown() {
            return "ok";
        }
    }
}
