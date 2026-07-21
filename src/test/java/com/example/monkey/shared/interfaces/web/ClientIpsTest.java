package com.example.monkey.shared.interfaces.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.monkey.shared.domain.security.TrustedProxyPolicy;
import jakarta.servlet.FilterChain;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ClientIpsTest {

    private static final String DIRECT_PUBLIC = "203.0.113.4";

    @Test
    void ignoresSpoofedForwardingHeadersFromUntrustedPeer() {
        MockHttpServletRequest request = requestFrom(DIRECT_PUBLIC);
        request.addHeader("X-Forwarded-For", "1.2.3.4");
        request.addHeader("Forwarded", "for=5.6.7.8");
        request.addHeader("X-Real-IP", "9.10.11.12");

        assertThat(ClientIps.resolve(request, trusts())).isEqualTo(DIRECT_PUBLIC);
    }

    @Test
    void walksTrustedProxyChainFromDirectPeerTowardClient() {
        MockHttpServletRequest request = requestFrom("10.0.0.10");
        request.addHeader("X-Forwarded-For", "1.2.3.4, 10.0.0.9");

        assertThat(ClientIps.resolve(request, trusts("10.0.0.10", "10.0.0.9"))).isEqualTo("1.2.3.4");
    }

    @Test
    void stopsAtFirstUntrustedAddressInsteadOfTakingFirstHeaderValue() {
        MockHttpServletRequest request = requestFrom("10.0.0.10");
        request.addHeader("X-Forwarded-For", "1.2.3.4, 172.16.0.9, 10.0.0.8");

        assertThat(ClientIps.resolve(request, trusts("10.0.0.10", "10.0.0.8"))).isEqualTo("172.16.0.9");
    }

    @Test
    void supportsIpv6ProxyCidrsAndCanonicalizesClientAddress() {
        MockHttpServletRequest request = requestFrom("2001:db8:ffff::10");
        request.addHeader("X-Forwarded-For", "2001:db8:1::7, 2001:db8:ffff::9");

        assertThat(ClientIps.resolve(request, trusts("2001:db8:ffff::10", "2001:db8:ffff::9")))
                .isEqualTo(canonical("2001:db8:1::7"));
    }

    @Test
    void parsesStandardForwardedHeaderWhenPeerChainIsTrusted() {
        MockHttpServletRequest request = requestFrom("10.0.0.10");
        request.addHeader("Forwarded", "for=1.2.3.4;proto=https, for=10.0.0.9;host=shop.example");

        assertThat(ClientIps.resolve(request, trusts("10.0.0.10", "10.0.0.9"))).isEqualTo("1.2.3.4");
    }

    @Test
    void malformedOrAmbiguousHeadersFailSafelyToDirectPeer() {
        MockHttpServletRequest malformed = requestFrom("10.0.0.10");
        malformed.addHeader("X-Forwarded-For", "1.2.3.4, not-an-ip");

        MockHttpServletRequest conflicting = requestFrom("10.0.0.10");
        conflicting.addHeader("X-Forwarded-For", "1.2.3.4, 10.0.0.9");
        conflicting.addHeader("Forwarded", "for=5.6.7.8, for=10.0.0.9");

        TrustedProxyPolicy policy = trusts("10.0.0.10", "10.0.0.9");
        assertThat(ClientIps.resolve(malformed, policy)).isEqualTo("10.0.0.10");
        assertThat(ClientIps.resolve(conflicting, policy)).isEqualTo("10.0.0.10");
    }

    @Test
    void overlongOrExcessiveHopHeadersFailSafelyToDirectPeer() {
        MockHttpServletRequest overlong = requestFrom("10.0.0.10");
        overlong.addHeader("X-Forwarded-For", "1".repeat(1_025));

        MockHttpServletRequest tooManyHops = requestFrom("10.0.0.10");
        String hops = IntStream.rangeClosed(1, 21)
                .mapToObj(index -> "10.0.0." + index)
                .collect(Collectors.joining(", "));
        tooManyHops.addHeader("X-Forwarded-For", hops);

        TrustedProxyPolicy trustAll = address -> true;
        assertThat(ClientIps.resolve(overlong, trustAll)).isEqualTo("10.0.0.10");
        assertThat(ClientIps.resolve(tooManyHops, trustAll)).isEqualTo("10.0.0.10");
    }

    @Test
    void emptyTrustPolicyIgnoresHeadersAndPublicResolveUsesPreResolvedAttributeOnly() {
        MockHttpServletRequest request = requestFrom("127.0.0.1");
        request.addHeader("X-Forwarded-For", "198.51.100.7");

        assertThat(ClientIps.resolve(request, trusts())).isEqualTo("127.0.0.1");
        assertThat(ClientIps.resolve(request)).isEqualTo("127.0.0.1");

        ClientIps.setResolved(request, "198.51.100.7");
        assertThat(ClientIps.resolve(request)).isEqualTo("198.51.100.7");
    }

    @Test
    void resolutionFilterPublishesOneEffectiveIpForDownstreamCallers() throws Exception {
        MockHttpServletRequest request = requestFrom("10.0.0.10");
        request.addHeader("X-Forwarded-For", "198.51.100.7, 10.0.0.9");
        MockHttpServletResponse response = new MockHttpServletResponse();
        ClientIpResolutionFilter filter = new ClientIpResolutionFilter(trusts("10.0.0.10", "10.0.0.9"));
        String[] downstreamIp = new String[1];
        FilterChain chain = (servletRequest, servletResponse) ->
                downstreamIp[0] = ClientIps.resolve((jakarta.servlet.http.HttpServletRequest) servletRequest);

        filter.doFilter(request, response, chain);

        assertThat(downstreamIp[0]).isEqualTo("198.51.100.7");
    }

    @Test
    void usesValidatedRealIpOnlyWhenForwardingHeadersAreAbsent() {
        MockHttpServletRequest request = requestFrom("10.0.0.10");
        request.addHeader("X-Real-IP", "198.51.100.7:443");

        assertThat(ClientIps.resolve(request, trusts("10.0.0.10"))).isEqualTo("198.51.100.7");

        request = requestFrom("10.0.0.10");
        request.addHeader("X-Real-IP", "[2001:db8::7]:8443");
        assertThat(ClientIps.resolve(request, trusts("10.0.0.10"))).isEqualTo(canonical("2001:db8::7"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "198.51.100.7:",
                "198.51.100.7:0",
                "198.51.100.7:65536",
                "198.51.100.7:100000",
                "198.51.100.7:https",
                "[2001:db8::7]8443",
                "[2001:db8::7]:0",
                "unknown",
                "_hidden"
            })
    void rejectsInvalidRealIpNodeIdentifiers(String realIp) {
        MockHttpServletRequest request = requestFrom("10.0.0.10");
        request.addHeader("X-Real-IP", realIp);

        assertThat(ClientIps.resolve(request, trusts("10.0.0.10"))).isEqualTo("10.0.0.10");
    }

    @Test
    void acceptsQuotedForwardedNodesAndRejectsMalformedQuotes() {
        MockHttpServletRequest quoted = requestFrom("10.0.0.10");
        quoted.addHeader("Forwarded", "for=\"198.51.100.7:443\"");
        assertThat(ClientIps.resolve(quoted, trusts("10.0.0.10"))).isEqualTo("198.51.100.7");

        for (String malformed :
                List.of("for=\"", "for=\"198.51.100.7", "for=198.51.100.7\"", "for=\"198.51\\.100.7\"")) {
            MockHttpServletRequest request = requestFrom("10.0.0.10");
            request.addHeader("Forwarded", malformed);
            assertThat(ClientIps.resolve(request, trusts("10.0.0.10"))).isEqualTo("10.0.0.10");
        }
    }

    @Test
    void failsClosedForBlankHeadersAndUnparseableDirectPeers() {
        MockHttpServletRequest blankHeader = requestFrom("10.0.0.10");
        blankHeader.addHeader("X-Real-IP", " ");
        assertThat(ClientIps.resolve(blankHeader, trusts("10.0.0.10"))).isEqualTo("10.0.0.10");

        MockHttpServletRequest blankPeer = requestFrom(" ");
        blankPeer.addHeader("X-Forwarded-For", "198.51.100.7");
        assertThat(ClientIps.resolve(blankPeer, address -> true)).isEqualTo("unknown");

        MockHttpServletRequest invalidPeer = requestFrom("not-an-ip");
        assertThat(ClientIps.resolve(invalidPeer, null)).isEqualTo("not-an-ip");
    }

    @Test
    void returnsLeftmostAddressWhenEveryProxyInTheChainIsTrusted() {
        MockHttpServletRequest request = requestFrom("10.0.0.10");
        request.addHeader("X-Forwarded-For", "10.0.0.7, 10.0.0.8, 10.0.0.9");

        assertThat(ClientIps.resolve(request, trusts("10.0.0.7", "10.0.0.8", "10.0.0.9", "10.0.0.10")))
                .isEqualTo("10.0.0.7");
    }

    private static MockHttpServletRequest requestFrom(String remoteAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddress);
        return request;
    }

    private static TrustedProxyPolicy trusts(String... addresses) {
        Set<String> trusted =
                Arrays.stream(addresses).map(ClientIpsTest::canonical).collect(Collectors.toSet());
        return address -> trusted.contains(address.getHostAddress());
    }

    private static String canonical(String address) {
        try {
            return InetAddress.getByName(address).getHostAddress();
        } catch (UnknownHostException exception) {
            throw new IllegalArgumentException(exception);
        }
    }
}
