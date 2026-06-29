package com.example.monkey.dto;

import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;

public record StatsQueryRequestDto(
        @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate start,
        @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate end) {}
