package com.example.monkey.shared.infrastructure.config;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.monkey.shared.application.observability.AuditService;
import com.example.monkey.shared.application.security.ApiRateLimitApplicationService;
import com.example.monkey.shared.application.security.ApiRateLimitResult;
import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.interfaces.security.ApiRateLimitFilter;
import com.example.monkey.shared.interfaces.web.VisitInterceptor;
import com.example.monkey.user.domain.UserAccountStore;
import jakarta.servlet.http.Cookie;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(controllers = SecurityConfigTest.TestApiController.class)
@Import({SecurityConfig.class, ApiRateLimitFilter.class, SecurityConfigTest.TestApiController.class})
@TestPropertySource(properties = "app.security.csp.upgrade-insecure-requests=false")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
@MockitoBean(
        types = {
            VisitInterceptor.class,
            UserAccountStore.class,
            AuditService.class,
            ApiRateLimitApplicationService.class,
            com.example.monkey.shared.domain.security.TrustedProxyPolicy.class
        })
class SpaCsrfIntegrationTest {

    private final MockMvc mockMvc;
    private final ApiRateLimitApplicationService apiRateLimitService;

    @Autowired
    SpaCsrfIntegrationTest(MockMvc mockMvc, ApiRateLimitApplicationService apiRateLimitService) {
        this.mockMvc = mockMvc;
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

    @Test
    void rawSpaCsrfCookieCanAuthorizeUnsafeRequest() throws Exception {
        MvcResult tokenResult = mockMvc.perform(get("/api/user/me").with(passwordChangeRequiredUser()))
                .andExpect(status().isOk())
                .andReturn();
        Cookie csrfCookie = tokenResult.getResponse().getCookie("XSRF-TOKEN");

        org.assertj.core.api.Assertions.assertThat(csrfCookie).isNotNull();
        mockMvc.perform(post("/api/user/update-password")
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue())
                        .with(passwordChangeRequiredUser()))
                .andExpect(status().isOk());
    }

    private static RequestPostProcessor passwordChangeRequiredUser() {
        var authorities = List.of(
                new SimpleGrantedAuthority("ROLE_USER"),
                new SimpleGrantedAuthority("USER_PROFILE_READ"),
                new SimpleGrantedAuthority("USER_PROFILE_WRITE"));
        var authentication =
                new UsernamePasswordAuthenticationToken(new SessionUser(7L, "USER", true), null, authorities);
        return authentication(authentication);
    }
}
