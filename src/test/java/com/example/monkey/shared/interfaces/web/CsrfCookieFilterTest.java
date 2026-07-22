package com.example.monkey.shared.interfaces.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;

class CsrfCookieFilterTest {

    private final CsrfCookieFilter filter = new CsrfCookieFilter();

    @Test
    void resolvesPresentTokenBeforeContinuingTheFilterChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        CsrfToken token = mock(CsrfToken.class);
        FilterChain chain = mock(FilterChain.class);
        request.setAttribute(CsrfToken.class.getName(), token);

        filter.doFilter(request, response, chain);

        verify(token).getToken();
        verify(chain).doFilter(request, response);
    }

    @Test
    void missingTokenStillContinuesTheFilterChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }
}
