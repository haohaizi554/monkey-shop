package com.example.monkey.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.monkey.domain.user.SessionUser;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class UserMdcFilterTest {

    private final UserMdcFilter filter = new UserMdcFilter();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    @Test
    void writesAuthenticatedUserIdToMdcForDownstreamLogsAndClearsIt() throws Exception {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(new SessionUser(42L, "USER"), null, List.of()));

        filter.doFilter(
                new MockHttpServletRequest(),
                new MockHttpServletResponse(),
                (request, response) ->
                        assertThat(MDC.get(TraceIds.USER_ID_MDC_KEY)).isEqualTo("42"));

        assertThat(MDC.get(TraceIds.USER_ID_MDC_KEY)).isNull();
    }
}
