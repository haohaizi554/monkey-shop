package com.example.monkey.membership.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.monkey.membership.domain.BrowseHistoryItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

class RedisMembershipActivityStoreTest {

    @Test
    void recordKeepsRecentBrowseHistoryWithFallbackTtl() {
        RedisMembershipActivityStore store =
                new RedisMembershipActivityStore(noRedis(), new ObjectMapper().findAndRegisterModules());
        LocalDateTime now = LocalDateTime.of(2026, 7, 4, 10, 0);

        store.record(new BrowseHistoryItem(1L, 9L, 101L, "A", null, now, now.plusDays(7)), Duration.ofDays(7));
        store.record(
                new BrowseHistoryItem(2L, 9L, 102L, "B", null, now.plusMinutes(1), now.plusDays(7)),
                Duration.ofDays(7));
        store.record(
                new BrowseHistoryItem(3L, 9L, 101L, "A2", null, now.plusMinutes(2), now.plusDays(7)),
                Duration.ofDays(7));

        assertThat(store.findRecent(9L, 10))
                .extracting(BrowseHistoryItem::productId)
                .containsExactly(101L, 102L);
        assertThat(store.findRecent(9L, 1)).hasSize(1);
    }

    private static ObjectProvider<StringRedisTemplate> noRedis() {
        return new ObjectProvider<>() {
            @Override
            public StringRedisTemplate getObject(Object... args) {
                return null;
            }

            @Override
            public StringRedisTemplate getIfAvailable() {
                return null;
            }

            @Override
            public StringRedisTemplate getIfUnique() {
                return null;
            }

            @Override
            public StringRedisTemplate getObject() {
                return null;
            }
        };
    }
}
