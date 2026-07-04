package com.example.monkey.membership.application.dto;

import java.time.LocalDate;

public record CheckInResponseDto(LocalDate checkInDate, int streakDays, long rewardPoints, PointsWalletDto wallet) {}
