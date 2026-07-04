package com.example.monkey.tracking.application.dto;

import java.time.LocalDateTime;
import java.util.List;

public record UserProfileTagDto(
        Long userId,
        String profileSummary,
        List<String> behaviorTags,
        List<String> interestTags,
        LocalDateTime lastEventAt,
        long version) {}
