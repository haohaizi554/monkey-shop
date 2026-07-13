package com.example.monkey.shared.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class TrustedProxyPropertiesTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void matchesConfiguredIpv4AndIpv6CidrsWithoutStringPrefixChecks() throws Exception {
        TrustedProxyProperties properties = new TrustedProxyProperties();
        properties.setTrustedProxyCidrs(List.of("10.0.0.0/8", "2001:db8:ffff::/48"));

        assertThat(properties.isTrusted(InetAddress.getByName("10.255.1.9"))).isTrue();
        assertThat(properties.isTrusted(InetAddress.getByName("11.0.0.1"))).isFalse();
        assertThat(properties.isTrusted(InetAddress.getByName("2001:db8:ffff::9")))
                .isTrue();
        assertThat(properties.isTrusted(InetAddress.getByName("2001:db8:fffe::9")))
                .isFalse();
    }

    @Test
    void matchesNonByteAlignedIpv4AndIpv6Prefixes() throws Exception {
        TrustedProxyProperties properties = new TrustedProxyProperties();
        properties.setTrustedProxyCidrs(List.of("192.0.2.128/25", "2001:db8:0:0:8000::/65"));

        assertThat(properties.isTrusted(InetAddress.getByName("192.0.2.200"))).isTrue();
        assertThat(properties.isTrusted(InetAddress.getByName("192.0.2.100"))).isFalse();
        assertThat(properties.isTrusted(InetAddress.getByName("2001:db8:0:0:9000::1")))
                .isTrue();
        assertThat(properties.isTrusted(InetAddress.getByName("2001:db8:0:0:7000::1")))
                .isFalse();
    }

    @Test
    void emptyConfigurationTrustsNoProxy() throws Exception {
        TrustedProxyProperties properties = new TrustedProxyProperties();

        assertThat(properties.isTrusted(InetAddress.getByName("127.0.0.1"))).isFalse();
        assertThat(properties.trustedProxyCidrs()).isEmpty();
    }

    @Test
    void bindsCommaSeparatedCidrsAsTypedConfiguration() {
        contextRunner
                .withPropertyValues("app.security.client-ip.trusted-proxy-cidrs=10.0.0.0/8,2001:db8:ffff::/48")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(TrustedProxyProperties.class).trustedProxyCidrs())
                            .containsExactly("10.0.0.0/8", "2001:db8:ffff::/48");
                });
    }

    @Test
    void invalidCidrFailsConfigurationBinding() {
        contextRunner
                .withPropertyValues("app.security.client-ip.trusted-proxy-cidrs=localhost/32")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalArgumentException.class)
                            .hasStackTraceContaining("Invalid trusted proxy CIDR");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(TrustedProxyProperties.class)
    static class PropertiesConfiguration {}
}
