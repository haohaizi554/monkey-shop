package com.example.monkey.marketing.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Canonical, stable request fingerprint for a group-buy create/join decision. */
public final class GroupBuyRequestFingerprint {

    private GroupBuyRequestFingerprint() {}

    public static String of(Long activityId, Long userId, Long teamId) {
        Objects.requireNonNull(activityId, "activity id is required");
        Objects.requireNonNull(userId, "user id is required");
        String canonical = "v1|activityId=" + activityId + "|userId=" + userId + "|teamId="
                + (teamId == null ? "null" : teamId);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest is unavailable", exception);
        }
    }
}
