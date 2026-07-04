package com.example.monkey.membership.domain;

import java.time.LocalDateTime;

public record MemberProfile(
        Long id,
        Long userId,
        MembershipLevel level,
        long growthValue,
        String realName,
        String realNameBlindIndex,
        String idCardNo,
        String idCardBlindIndex,
        LocalDateTime verifiedAt,
        long version,
        LocalDateTime createTime,
        LocalDateTime updateTime) {

    public MemberProfile {
        level = level == null ? MembershipLevel.BASIC : level;
        growthValue = Math.max(0, growthValue);
    }

    public boolean verified() {
        return verifiedAt != null;
    }

    public MemberProfile verifyIdentity(
            String newRealName,
            String newRealNameBlindIndex,
            String newIdCardNo,
            String newIdCardBlindIndex,
            LocalDateTime now) {
        return new MemberProfile(
                id,
                userId,
                level,
                growthValue,
                newRealName,
                newRealNameBlindIndex,
                newIdCardNo,
                newIdCardBlindIndex,
                now,
                version,
                createTime,
                now);
    }

    public MemberProfile addGrowth(long delta, LocalDateTime now) {
        long nextGrowth = Math.max(0, growthValue + delta);
        return new MemberProfile(
                id,
                userId,
                MembershipLevel.fromGrowth(nextGrowth),
                nextGrowth,
                realName,
                realNameBlindIndex,
                idCardNo,
                idCardBlindIndex,
                verifiedAt,
                version,
                createTime,
                now);
    }

    public MemberProfile withLevel(MembershipLevel nextLevel, LocalDateTime now) {
        return new MemberProfile(
                id,
                userId,
                nextLevel,
                growthValue,
                realName,
                realNameBlindIndex,
                idCardNo,
                idCardBlindIndex,
                verifiedAt,
                version,
                createTime,
                now);
    }
}
