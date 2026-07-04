package com.example.monkey.membership.application.dto;

import com.example.monkey.membership.domain.MembershipLevel;
import java.util.List;

public record MemberProfileDto(
        Long userId,
        MembershipLevel level,
        long growthValue,
        boolean verified,
        String maskedRealName,
        String maskedIdCardNo,
        long version,
        List<String> benefits) {}
