package com.example.monkey.shared.infrastructure.config;

import com.example.monkey.shared.domain.security.IpAddressParser;
import com.example.monkey.shared.domain.security.TrustedProxyPolicy;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "app.security.client-ip")
public class TrustedProxyProperties implements TrustedProxyPolicy {

    private List<String> trustedProxyCidrs = List.of();
    private List<CidrRange> trustedRanges = List.of();

    public List<String> getTrustedProxyCidrs() {
        return trustedProxyCidrs;
    }

    public List<String> trustedProxyCidrs() {
        return trustedProxyCidrs;
    }

    public void setTrustedProxyCidrs(List<String> cidrs) {
        List<String> normalized = cidrs == null
                ? List.of()
                : cidrs.stream().filter(StringUtils::hasText).map(String::trim).toList();
        List<CidrRange> parsed = new ArrayList<>(normalized.size());
        for (String cidr : normalized) {
            parsed.add(CidrRange.parse(cidr));
        }
        this.trustedProxyCidrs = List.copyOf(normalized);
        this.trustedRanges = List.copyOf(parsed);
    }

    @Override
    public boolean isTrusted(InetAddress address) {
        if (address == null) {
            return false;
        }
        return trustedRanges.stream().anyMatch(range -> range.contains(address));
    }

    private record CidrRange(byte[] network, int prefixLength) {

        private static CidrRange parse(String cidr) {
            int slash = cidr.indexOf('/');
            if (slash <= 0 || slash != cidr.lastIndexOf('/') || slash == cidr.length() - 1) {
                throw invalid(cidr);
            }
            InetAddress address =
                    IpAddressParser.parseLiteral(cidr.substring(0, slash)).orElseThrow(() -> invalid(cidr));
            int prefix;
            try {
                prefix = Integer.parseInt(cidr.substring(slash + 1));
            } catch (NumberFormatException exception) {
                throw invalid(cidr);
            }
            byte[] network = address.getAddress().clone();
            int addressBits = network.length * Byte.SIZE;
            if (prefix < 0 || prefix > addressBits) {
                throw invalid(cidr);
            }
            maskHostBits(network, prefix);
            return new CidrRange(network, prefix);
        }

        private boolean contains(InetAddress address) {
            byte[] candidate = address.getAddress();
            if (candidate.length != network.length) {
                return false;
            }
            int wholeBytes = prefixLength / Byte.SIZE;
            int remainingBits = prefixLength % Byte.SIZE;
            for (int index = 0; index < wholeBytes; index++) {
                if (candidate[index] != network[index]) {
                    return false;
                }
            }
            if (remainingBits == 0) {
                return true;
            }
            int mask = (0xFF << (Byte.SIZE - remainingBits)) & 0xFF;
            return ((candidate[wholeBytes] & 0xFF) & mask) == ((network[wholeBytes] & 0xFF) & mask);
        }

        private static void maskHostBits(byte[] address, int prefixLength) {
            int wholeBytes = prefixLength / Byte.SIZE;
            int remainingBits = prefixLength % Byte.SIZE;
            if (remainingBits > 0 && wholeBytes < address.length) {
                int mask = (0xFF << (Byte.SIZE - remainingBits)) & 0xFF;
                address[wholeBytes] = (byte) ((address[wholeBytes] & 0xFF) & mask);
                wholeBytes++;
            }
            for (int index = wholeBytes; index < address.length; index++) {
                address[index] = 0;
            }
        }

        private static IllegalArgumentException invalid(String cidr) {
            return new IllegalArgumentException("Invalid trusted proxy CIDR: " + cidr);
        }
    }
}
