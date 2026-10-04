package com.example.monkey.membership.application.dto;

import com.example.monkey.membership.domain.MembershipLevel;
import com.example.monkey.membership.domain.IdentityVerificationStatus;
import java.time.LocalDateTime;
import java.util.List;

public record MemberProfileDto(
        Long userId,
        MembershipLevel level,
        long growthValue,
        IdentityVerificationStatus identityStatus,
        boolean verified,
        String maskedRealName,
        String maskedIdCardNo,
        LocalDateTime identitySubmittedAt,
        LocalDateTime identityReviewedAt,
        long version,
        List<String> benefits) {}
