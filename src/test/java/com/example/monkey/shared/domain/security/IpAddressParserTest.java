package com.example.monkey.shared.domain.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;

class IpAddressParserTest {

    @Test
    void parsesIpv6LiteralBeginningWithCompressionMarker() throws UnknownHostException {
        assertThat(IpAddressParser.parseLiteral("::1")).contains(InetAddress.getByName("::1"));
    }
}
