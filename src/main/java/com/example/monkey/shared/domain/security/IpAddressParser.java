package com.example.monkey.shared.domain.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;

public final class IpAddressParser {

    private IpAddressParser() {}

    public static Optional<InetAddress> parseLiteral(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String candidate = value.trim();
        if (candidate.isEmpty()) {
            return Optional.empty();
        }
        return candidate.indexOf(':') >= 0 ? parseIpv6(candidate) : parseIpv4(candidate);
    }

    private static Optional<InetAddress> parseIpv4(String candidate) {
        String[] octets = candidate.split("\\.", -1);
        if (octets.length != 4) {
            return Optional.empty();
        }
        byte[] address = new byte[4];
        for (int index = 0; index < octets.length; index++) {
            String octet = octets[index];
            if (octet.isEmpty() || octet.length() > 3 || !octet.chars().allMatch(Character::isDigit)) {
                return Optional.empty();
            }
            if (octet.length() > 1 && octet.charAt(0) == '0') {
                return Optional.empty();
            }
            int parsed;
            try {
                parsed = Integer.parseInt(octet);
            } catch (NumberFormatException exception) {
                return Optional.empty();
            }
            if (parsed > 255) {
                return Optional.empty();
            }
            address[index] = (byte) parsed;
        }
        try {
            return Optional.of(InetAddress.getByAddress(address));
        } catch (UnknownHostException exception) {
            return Optional.empty();
        }
    }

    private static Optional<InetAddress> parseIpv6(String candidate) {
        if (!candidate.matches("[0-9A-Fa-f:.]+")) {
            return Optional.empty();
        }
        try {
            InetAddress address = InetAddress.getByName(candidate);
            return address.getAddress().length == 16 ? Optional.of(address) : Optional.empty();
        } catch (UnknownHostException exception) {
            return Optional.empty();
        }
    }
}
