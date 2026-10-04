package com.example.monkey.membership.domain;

import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
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
        IdentityVerificationStatus identityStatus,
        LocalDateTime identitySubmittedAt,
        LocalDateTime identityReviewedAt,
        Long identityReviewedBy,
        String identityReviewReason,
        LocalDateTime verifiedAt,
        long version,
        LocalDateTime createTime,
        LocalDateTime updateTime) {

    public MemberProfile {
        level = level == null ? MembershipLevel.BASIC : level;
        growthValue = Math.max(0, growthValue);
        identityStatus = identityStatus == null
                ? (verifiedAt == null ? IdentityVerificationStatus.PENDING : IdentityVerificationStatus.VERIFIED)
                : identityStatus;
        if (identityStatus != IdentityVerificationStatus.VERIFIED) {
            verifiedAt = null;
        }
    }

    /** Compatibility constructor for callers written before identity review metadata was introduced. */
    public MemberProfile(
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
        this(
                id,
                userId,
                level,
                growthValue,
                realName,
                realNameBlindIndex,
                idCardNo,
                idCardBlindIndex,
                verifiedAt == null ? IdentityVerificationStatus.PENDING : IdentityVerificationStatus.VERIFIED,
                verifiedAt,
                verifiedAt,
                null,
                null,
                verifiedAt,
                version,
                createTime,
                updateTime);
    }

    public boolean verified() {
        return identityStatus == IdentityVerificationStatus.VERIFIED;
    }

    /**
     * Submit identity data for a human review. Submission never establishes verified status.
     */
    public MemberProfile submitIdentity(
            String newRealName,
            String newRealNameBlindIndex,
            String newIdCardNo,
            String newIdCardBlindIndex,
            LocalDateTime now) {
        if (identityStatus == IdentityVerificationStatus.VERIFIED) {
            throw new BusinessException(ErrorCode.CONFLICT, "Verified identity requires an administrator review");
        }
        return new MemberProfile(
                id,
                userId,
                level,
                growthValue,
                newRealName,
                newRealNameBlindIndex,
                newIdCardNo,
                newIdCardBlindIndex,
                IdentityVerificationStatus.PENDING,
                now,
                null,
                null,
                null,
                null,
                version,
                createTime,
                now);
    }

    /**
     * Kept as a source-compatible alias; it now means submit for review rather than verify.
     */
    public MemberProfile verifyIdentity(
            String newRealName,
            String newRealNameBlindIndex,
            String newIdCardNo,
            String newIdCardBlindIndex,
            LocalDateTime now) {
        return submitIdentity(newRealName, newRealNameBlindIndex, newIdCardNo, newIdCardBlindIndex, now);
    }

    public MemberProfile reviewIdentity(
            IdentityVerificationStatus decision, Long reviewerUserId, String reason, LocalDateTime now) {
        if (decision == null || decision == IdentityVerificationStatus.PENDING) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Identity review decision is required");
        }
        if (identityStatus != IdentityVerificationStatus.PENDING) {
            throw new BusinessException(ErrorCode.CONFLICT, "Only pending identity submissions can be reviewed");
        }
        if (identitySubmittedAt == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "Identity submission is required before review");
        }
        return new MemberProfile(
                id,
                userId,
                level,
                growthValue,
                realName,
                realNameBlindIndex,
                idCardNo,
                idCardBlindIndex,
                decision,
                identitySubmittedAt,
                now,
                reviewerUserId,
                reason,
                decision == IdentityVerificationStatus.VERIFIED ? now : null,
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
                identityStatus,
                identitySubmittedAt,
                identityReviewedAt,
                identityReviewedBy,
                identityReviewReason,
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
                identityStatus,
                identitySubmittedAt,
                identityReviewedAt,
                identityReviewedBy,
                identityReviewReason,
                verifiedAt,
                version,
                createTime,
                now);
    }
}
