package com.example.monkey.membership.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record MembershipCheckIn(
        Long id,
        Long userId,
        LocalDate checkInDate,
        int streakDays,
        long rewardPoints,
        String idempotencyKey,
        LocalDateTime createdAt) {}
