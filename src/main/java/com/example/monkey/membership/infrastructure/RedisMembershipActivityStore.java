package com.example.monkey.membership.infrastructure;

import com.example.monkey.membership.domain.BrowseHistoryItem;
import com.example.monkey.membership.domain.MembershipActivityStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisMembershipActivityStore implements MembershipActivityStore {

    private static final String KEY_PREFIX = "membership:browse:user:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Map<Long, Map<Long, BrowseHistoryItem>> fallback = new ConcurrentHashMap<>();

    public RedisMembershipActivityStore(
            ObjectProvider<StringRedisTemplate> redisTemplateProvider, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
        this.objectMapper = objectMapper;
    }

    @Override
    public BrowseHistoryItem record(BrowseHistoryItem item, Duration ttl) {
        if (redisTemplate == null) {
            fallback.computeIfAbsent(item.userId(), ignored -> new ConcurrentHashMap<>())
                    .put(item.productId(), item);
            return item;
        }
        String key = key(item.userId());
        try {
            removeProduct(key, item.productId());
            redisTemplate.opsForZSet().add(key, serialize(item), score(item.viewedAt()));
            redisTemplate.expire(key, ttl);
        } catch (RuntimeException exception) {
            fallback.computeIfAbsent(item.userId(), ignored -> new ConcurrentHashMap<>())
                    .put(item.productId(), item);
        }
        return item;
    }

    @Override
    public List<BrowseHistoryItem> findRecent(Long userId, int limit) {
        if (redisTemplate == null) {
            return fallbackRecent(userId, limit);
        }
        try {
            Set<String> values = redisTemplate.opsForZSet().reverseRange(key(userId), 0, Math.max(0, limit - 1));
            if (values == null) {
                return List.of();
            }
            return values.stream().map(this::deserialize).toList();
        } catch (RuntimeException exception) {
            return fallbackRecent(userId, limit);
        }
    }

    private List<BrowseHistoryItem> fallbackRecent(Long userId, int limit) {
        LocalDateTime now = LocalDateTime.now();
        return fallback.computeIfAbsent(userId, ignored -> new ConcurrentHashMap<>()).values().stream()
                .filter(item -> item.expiresAt() == null || item.expiresAt().isAfter(now))
                .sorted(Comparator.comparing(BrowseHistoryItem::viewedAt).reversed())
                .limit(Math.max(1, limit))
                .toList();
    }

    private void removeProduct(String key, Long productId) {
        Set<String> values = redisTemplate.opsForZSet().range(key, 0, -1);
        if (values == null) {
            return;
        }
        for (String value : values) {
            if (deserialize(value).productId().equals(productId)) {
                redisTemplate.opsForZSet().remove(key, value);
            }
        }
    }

    private String serialize(BrowseHistoryItem item) {
        try {
            return objectMapper.writeValueAsString(item);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Browse history item cannot be serialized", exception);
        }
    }

    private BrowseHistoryItem deserialize(String value) {
        try {
            return objectMapper.readValue(value, BrowseHistoryItem.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Browse history item cannot be deserialized", exception);
        }
    }

    private static double score(LocalDateTime viewedAt) {
        return viewedAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private static String key(Long userId) {
        return KEY_PREFIX + userId;
    }
}
