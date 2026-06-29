package com.example.monkey.shared.interfaces.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientIpsTest {

    @Test
    void resolvesFirstForwardedForAddress() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "203.0.113.10, 10.0.0.5");

        assertThat(ClientIps.resolve(request)).isEqualTo("203.0.113.10");
    }

    @Test
    void fallsBackToRealIpAndRemoteAddress() {
        MockHttpServletRequest realIpRequest = new MockHttpServletRequest();
        realIpRequest.addHeader("X-Real-IP", "198.51.100.7");
        MockHttpServletRequest remoteRequest = new MockHttpServletRequest();
        remoteRequest.setRemoteAddr("192.0.2.22");

        assertThat(ClientIps.resolve(realIpRequest)).isEqualTo("198.51.100.7");
        assertThat(ClientIps.resolve(remoteRequest)).isEqualTo("192.0.2.22");
    }
}
