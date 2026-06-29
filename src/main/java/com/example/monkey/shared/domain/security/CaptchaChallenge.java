package com.example.monkey.shared.domain.security;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

public final class CaptchaChallenge {

    private final String provider;
    private final String siteKey;
    private final String challengeId;
    private final Duration ttl;
    private final boolean cookieSecure;
    private final String contentType;
    private final byte[] content;

    private CaptchaChallenge(
            String provider,
            String siteKey,
            String challengeId,
            Duration ttl,
            boolean cookieSecure,
            String contentType,
            byte[] content) {
        this.provider = provider;
        this.siteKey = siteKey;
        this.challengeId = challengeId;
        this.ttl = ttl;
        this.cookieSecure = cookieSecure;
        this.contentType = contentType;
        this.content = content == null ? new byte[0] : Arrays.copyOf(content, content.length);
    }

    public static CaptchaChallenge local(
            String challengeId, Duration ttl, boolean cookieSecure, String contentType, byte[] content) {
        return new CaptchaChallenge("local", "", challengeId, ttl, cookieSecure, contentType, content);
    }

    public static CaptchaChallenge external(String provider, String siteKey) {
        return new CaptchaChallenge(provider, siteKey, null, Duration.ZERO, false, null, new byte[0]);
    }

    public String provider() {
        return provider;
    }

    public String siteKey() {
        return siteKey;
    }

    public Optional<String> challengeId() {
        return Optional.ofNullable(challengeId);
    }

    public Duration ttl() {
        return ttl;
    }

    public boolean cookieSecure() {
        return cookieSecure;
    }

    public Optional<String> contentType() {
        return Optional.ofNullable(contentType);
    }

    public byte[] content() {
        return Arrays.copyOf(content, content.length);
    }

    public boolean externalProvider() {
        return content.length == 0;
    }
}
