package com.example.monkey.shared.interfaces.web;

import com.example.monkey.shared.domain.security.IpAddressParser;
import com.example.monkey.shared.domain.security.TrustedProxyPolicy;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.util.StringUtils;

public final class ClientIps {

    static final int MAX_FORWARDING_HEADER_LENGTH = 1_024;
    static final int MAX_FORWARDING_HOPS = 20;
    private static final String RESOLVED_ATTRIBUTE = ClientIps.class.getName() + ".resolved";
    private static final String X_FORWARDED_FOR = "X-Forwarded-For";
    private static final String FORWARDED = "Forwarded";
    private static final String X_REAL_IP = "X-Real-IP";

    private ClientIps() {}

    public static String resolve(HttpServletRequest request) {
        Object resolved = request.getAttribute(RESOLVED_ATTRIBUTE);
        return resolved instanceof String value && StringUtils.hasText(value) ? value : directPeer(request);
    }

    static String resolve(HttpServletRequest request, TrustedProxyPolicy trustedProxyPolicy) {
        String directPeer = directPeer(request);
        Optional<InetAddress> directAddress = IpAddressParser.parseLiteral(directPeer);
        if (directAddress.isEmpty()
                || trustedProxyPolicy == null
                || !trustedProxyPolicy.isTrusted(directAddress.orElseThrow())) {
            return directPeer;
        }

        ParsedChain forwardedFor = parseForwardedFor(readHeader(request, X_FORWARDED_FOR));
        ParsedChain forwarded = parseForwarded(readHeader(request, FORWARDED));
        if (!forwardedFor.valid() || !forwarded.valid()) {
            return directPeer;
        }
        if (forwardedFor.present() && forwarded.present()) {
            String xForwardedResult = resolveChain(forwardedFor.addresses(), trustedProxyPolicy);
            String forwardedResult = resolveChain(forwarded.addresses(), trustedProxyPolicy);
            return xForwardedResult.equals(forwardedResult) ? xForwardedResult : directPeer;
        }
        if (forwardedFor.present()) {
            return resolveChain(forwardedFor.addresses(), trustedProxyPolicy);
        }
        if (forwarded.present()) {
            return resolveChain(forwarded.addresses(), trustedProxyPolicy);
        }

        HeaderValue realIp = readHeader(request, X_REAL_IP);
        if (!realIp.valid()) {
            return directPeer;
        }
        return realIp.present()
                ? parseNodeIdentifier(realIp.value())
                        .map(InetAddress::getHostAddress)
                        .orElse(directPeer)
                : directPeer;
    }

    static void setResolved(HttpServletRequest request, String clientIp) {
        request.setAttribute(RESOLVED_ATTRIBUTE, clientIp);
    }

    private static String resolveChain(List<InetAddress> addresses, TrustedProxyPolicy trustedProxyPolicy) {
        for (int index = addresses.size() - 1; index >= 0; index--) {
            InetAddress address = addresses.get(index);
            if (!trustedProxyPolicy.isTrusted(address)) {
                return address.getHostAddress();
            }
        }
        return addresses.get(0).getHostAddress();
    }

    private static ParsedChain parseForwardedFor(HeaderValue header) {
        if (!header.present() || !header.valid()) {
            return new ParsedChain(header.present(), header.valid(), List.of());
        }
        return parseElements(header.value(), ClientIps::parseNodeIdentifier);
    }

    private static ParsedChain parseForwarded(HeaderValue header) {
        if (!header.present() || !header.valid()) {
            return new ParsedChain(header.present(), header.valid(), List.of());
        }
        return parseElements(header.value(), ClientIps::parseForwardedElement);
    }

    private static ParsedChain parseElements(String value, AddressElementParser parser) {
        String[] elements = value.split(",", -1);
        if (elements.length == 0 || elements.length > MAX_FORWARDING_HOPS) {
            return ParsedChain.invalid();
        }
        List<InetAddress> addresses = new ArrayList<>(elements.length);
        for (String element : elements) {
            Optional<InetAddress> address = parser.parse(element.trim());
            if (address.isEmpty()) {
                return ParsedChain.invalid();
            }
            addresses.add(address.orElseThrow());
        }
        return new ParsedChain(true, true, List.copyOf(addresses));
    }

    private static Optional<InetAddress> parseForwardedElement(String element) {
        String found = null;
        for (String parameter : element.split(";", -1)) {
            String[] pair = parameter.trim().split("=", 2);
            if (pair.length != 2 || !"for".equals(pair[0].trim().toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (found != null) {
                return Optional.empty();
            }
            found = unquote(pair[1].trim()).orElse(null);
        }
        return found == null ? Optional.empty() : parseNodeIdentifier(found);
    }

    private static Optional<String> unquote(String value) {
        if (!value.startsWith("\"") && !value.endsWith("\"")) {
            return Optional.of(value);
        }
        if (value.length() < 2 || !value.startsWith("\"") || !value.endsWith("\"") || value.indexOf('\\') >= 0) {
            return Optional.empty();
        }
        return Optional.of(value.substring(1, value.length() - 1));
    }

    private static Optional<InetAddress> parseNodeIdentifier(String value) {
        if (!StringUtils.hasText(value)) {
            return Optional.empty();
        }
        String candidate = value.trim();
        if (candidate.startsWith("[") && candidate.contains("]")) {
            int close = candidate.indexOf(']');
            String remainder = candidate.substring(close + 1);
            if (!remainder.isEmpty() && !validPortSuffix(remainder)) {
                return Optional.empty();
            }
            candidate = candidate.substring(1, close);
        } else if (candidate.chars().filter(character -> character == ':').count() == 1L
                && candidate.indexOf('.') >= 0) {
            int colon = candidate.lastIndexOf(':');
            String port = candidate.substring(colon + 1);
            if (!validPort(port)) {
                return Optional.empty();
            }
            candidate = candidate.substring(0, colon);
        }
        if ("unknown".equalsIgnoreCase(candidate) || candidate.startsWith("_")) {
            return Optional.empty();
        }
        return IpAddressParser.parseLiteral(candidate);
    }

    private static boolean validPortSuffix(String value) {
        return value.startsWith(":") && validPort(value.substring(1));
    }

    private static boolean validPort(String value) {
        if (value.isEmpty() || value.length() > 5 || !value.chars().allMatch(Character::isDigit)) {
            return false;
        }
        try {
            int port = Integer.parseInt(value);
            return port >= 1 && port <= 65_535;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static HeaderValue readHeader(HttpServletRequest request, String name) {
        Enumeration<String> values = request.getHeaders(name);
        if (values == null || !values.hasMoreElements()) {
            return HeaderValue.absent();
        }
        StringBuilder combined = new StringBuilder();
        while (values.hasMoreElements()) {
            String value = values.nextElement();
            if (!StringUtils.hasText(value)) {
                return HeaderValue.invalid();
            }
            if (!combined.isEmpty()) {
                combined.append(',');
            }
            combined.append(value.trim());
            if (combined.length() > MAX_FORWARDING_HEADER_LENGTH) {
                return HeaderValue.invalid();
            }
        }
        return new HeaderValue(true, true, combined.toString());
    }

    private static String directPeer(HttpServletRequest request) {
        String remoteAddress = request.getRemoteAddr();
        if (!StringUtils.hasText(remoteAddress)) {
            return "unknown";
        }
        String trimmed = remoteAddress.trim();
        return IpAddressParser.parseLiteral(trimmed)
                .map(InetAddress::getHostAddress)
                .orElse(trimmed);
    }

    @FunctionalInterface
    private interface AddressElementParser {

        Optional<InetAddress> parse(String value);
    }

    private record HeaderValue(boolean present, boolean valid, String value) {

        private static HeaderValue absent() {
            return new HeaderValue(false, true, "");
        }

        private static HeaderValue invalid() {
            return new HeaderValue(true, false, "");
        }
    }

    private record ParsedChain(boolean present, boolean valid, List<InetAddress> addresses) {

        private static ParsedChain invalid() {
            return new ParsedChain(true, false, List.of());
        }
    }
}
