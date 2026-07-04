package com.example.monkey.search.domain;

import java.time.LocalDateTime;
import java.util.List;

public record UserSearchProfile(
        Long userId, String interestProfile, List<String> tags, LocalDateTime updatedAt, long version) {

    public UserSearchProfile {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
