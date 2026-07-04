package com.example.monkey.membership.application.dto;

import com.example.monkey.membership.domain.MembershipLevel;
import jakarta.validation.constraints.NotNull;

public record LevelChangeRequestDto(@NotNull MembershipLevel level, String reason, String totpCode) {}
