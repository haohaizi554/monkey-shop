package com.example.monkey.shared.domain.security;

import java.net.InetAddress;

@FunctionalInterface
public interface TrustedProxyPolicy {

    boolean isTrusted(InetAddress address);
}
