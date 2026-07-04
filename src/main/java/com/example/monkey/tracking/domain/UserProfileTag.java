package com.example.monkey.tracking.domain;

import java.time.LocalDateTime;
import java.util.List;

public record UserProfileTag(
        Long userId,
        String profileSummary,
        List<String> behaviorTags,
        List<String> interestTags,
        LocalDateTime lastEventAt,
        long version) {

    public UserProfileTag {
        behaviorTags = behaviorTags == null ? List.of() : List.copyOf(behaviorTags);
        interestTags = interestTags == null ? List.of() : List.copyOf(interestTags);
        lastEventAt = lastEventAt == null ? LocalDateTime.now() : lastEventAt;
        profileSummary = profileSummary == null ? "" : profileSummary;
    }
}
