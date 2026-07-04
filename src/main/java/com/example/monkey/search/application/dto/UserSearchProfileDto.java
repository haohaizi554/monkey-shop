package com.example.monkey.search.application.dto;

import java.time.LocalDateTime;
import java.util.List;

public record UserSearchProfileDto(
        Long userId, String maskedInterestProfile, List<String> tags, LocalDateTime updatedAt, long version) {}
