package com.example.monkey.shared.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.util.List;
import org.junit.jupiter.api.Test;

class TrustedProxyPropertiesCidrBoundaryTest {

    @Test
    void masksHostBitsBeforeMatchingIpv4AndIpv6Networks() throws Exception {
        TrustedProxyProperties properties = new TrustedProxyProperties();
        properties.setTrustedProxyCidrs(List.of("192.0.2.255/25", "2001:db8:0:0:ffff::1/65"));

        assertThat(properties.isTrusted(InetAddress.getByName("192.0.2.128"))).isTrue();
        assertThat(properties.isTrusted(InetAddress.getByName("192.0.2.254"))).isTrue();
        assertThat(properties.isTrusted(InetAddress.getByName("192.0.2.127"))).isFalse();
        assertThat(properties.isTrusted(InetAddress.getByName("2001:db8:0:0:8000::")))
                .isTrue();
        assertThat(properties.isTrusted(InetAddress.getByName("2001:db8:0:0:7fff:ffff:ffff:ffff")))
                .isFalse();
    }

    @Test
    void supportsZeroAndFullLengthIpv4Prefixes() throws Exception {
        TrustedProxyProperties anyIpv4 = new TrustedProxyProperties();
        anyIpv4.setTrustedProxyCidrs(List.of("203.0.113.77/0"));
        TrustedProxyProperties exactIpv4 = new TrustedProxyProperties();
        exactIpv4.setTrustedProxyCidrs(List.of("203.0.113.77/32"));

        assertThat(anyIpv4.isTrusted(InetAddress.getByName("0.0.0.0"))).isTrue();
        assertThat(anyIpv4.isTrusted(InetAddress.getByName("255.255.255.255"))).isTrue();
        assertThat(anyIpv4.isTrusted(InetAddress.getByName("2001:db8::1"))).isFalse();
        assertThat(exactIpv4.isTrusted(InetAddress.getByName("203.0.113.77"))).isTrue();
        assertThat(exactIpv4.isTrusted(InetAddress.getByName("203.0.113.76"))).isFalse();
    }

    @Test
    void supportsZeroAndFullLengthIpv6Prefixes() throws Exception {
        TrustedProxyProperties anyIpv6 = new TrustedProxyProperties();
        anyIpv6.setTrustedProxyCidrs(List.of("2001:db8::1/0"));
        TrustedProxyProperties exactIpv6 = new TrustedProxyProperties();
        exactIpv6.setTrustedProxyCidrs(List.of("2001:db8::1/128"));

        assertThat(anyIpv6.isTrusted(InetAddress.getByName("::"))).isTrue();
        assertThat(anyIpv6.isTrusted(InetAddress.getByName("ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff")))
                .isTrue();
        assertThat(anyIpv6.isTrusted(InetAddress.getByName("192.0.2.1"))).isFalse();
        assertThat(exactIpv6.isTrusted(InetAddress.getByName("2001:db8::1"))).isTrue();
        assertThat(exactIpv6.isTrusted(InetAddress.getByName("2001:db8::2"))).isFalse();
    }

    @Test
    void rejectsMalformedPrefixSeparatorsAndMissingParts() {
        assertInvalid("/24");
        assertInvalid("192.0.2.1/");
        assertInvalid("192.0.2.1/24/7");
        assertInvalid("192.0.2.1");
    }

    @Test
    void rejectsNonNumericAndOutOfRangePrefixes() {
        assertInvalid("192.0.2.1/not-a-number");
        assertInvalid("192.0.2.1/-1");
        assertInvalid("192.0.2.1/33");
        assertInvalid("2001:db8::1/129");
    }

    @Test
    void normalizesConfiguredValuesAndRejectsNullAddresses() {
        TrustedProxyProperties properties = new TrustedProxyProperties();
        properties.setTrustedProxyCidrs(List.of(" 10.0.0.1/8 ", " ", "\t"));

        assertThat(properties.trustedProxyCidrs()).containsExactly("10.0.0.1/8");
        assertThat(properties.getTrustedProxyCidrs()).containsExactly("10.0.0.1/8");
        assertThat(properties.isTrusted(null)).isFalse();

        properties.setTrustedProxyCidrs(null);

        assertThat(properties.trustedProxyCidrs()).isEmpty();
    }

    private static void assertInvalid(String cidr) {
        TrustedProxyProperties properties = new TrustedProxyProperties();

        assertThatThrownBy(() -> properties.setTrustedProxyCidrs(List.of(cidr)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid trusted proxy CIDR: " + cidr);
    }
}
