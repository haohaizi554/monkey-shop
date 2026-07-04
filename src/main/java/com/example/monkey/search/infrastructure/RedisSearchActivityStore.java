package com.example.monkey.search.infrastructure;

import com.example.monkey.search.domain.HotKeyword;
import com.example.monkey.search.domain.SearchActivityStore;
import com.example.monkey.search.domain.SearchSuggestion;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

@Component
public class RedisSearchActivityStore implements SearchActivityStore {

    private static final String HOT_KEY = "search:hot-keywords";
    private static final String SUGGEST_PREFIX = "search:suggest:";
    private static final String SNAPSHOT_KEY = "search:hot-keywords:snapshot";

    private final StringRedisTemplate redisTemplate;
    private final Map<String, Long> fallbackHotKeywords = new ConcurrentHashMap<>();
    private final Map<String, List<SearchSuggestion>> fallbackSuggestions = new ConcurrentHashMap<>();
    private volatile List<HotKeyword> fallbackSnapshot = List.of();

    public RedisSearchActivityStore(ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
    }

    @Override
    public void recordKeyword(String keyword) {
        String normalized = normalize(keyword);
        if (normalized.isBlank()) {
            return;
        }
        if (redisTemplate == null) {
            fallbackHotKeywords.merge(normalized, 1L, Long::sum);
            return;
        }
        try {
            redisTemplate.opsForZSet().incrementScore(HOT_KEY, normalized, 1);
        } catch (RuntimeException exception) {
            fallbackHotKeywords.merge(normalized, 1L, Long::sum);
        }
    }

    @Override
    public List<HotKeyword> hotKeywords(int limit) {
        if (redisTemplate == null) {
            return fallbackHotKeywords(limit);
        }
        try {
            Set<ZSetOperations.TypedTuple<String>> tuples =
                    redisTemplate.opsForZSet().reverseRangeWithScores(HOT_KEY, 0, Math.max(0, limit - 1));
            if (tuples == null || tuples.isEmpty()) {
                return fallbackHotKeywords(limit);
            }
            return tuples.stream()
                    .map(tuple -> new HotKeyword(
                            tuple.getValue(), Math.round(tuple.getScore() == null ? 0 : tuple.getScore())))
                    .toList();
        } catch (RuntimeException exception) {
            return fallbackHotKeywords(limit);
        }
    }

    @Override
    public List<SearchSuggestion> suggestions(String prefix, int limit) {
        String normalized = normalize(prefix);
        if (redisTemplate == null) {
            return fallbackSuggestions(normalized, limit);
        }
        try {
            Set<ZSetOperations.TypedTuple<String>> tuples = redisTemplate
                    .opsForZSet()
                    .reverseRangeWithScores(suggestionKey(normalized), 0, Math.max(0, limit - 1));
            if (tuples == null || tuples.isEmpty()) {
                return fallbackSuggestions(normalized, limit);
            }
            return tuples.stream()
                    .map(tuple -> new SearchSuggestion(
                            tuple.getValue(), "cache", Math.round(tuple.getScore() == null ? 0 : tuple.getScore())))
                    .toList();
        } catch (RuntimeException exception) {
            return fallbackSuggestions(normalized, limit);
        }
    }

    @Override
    public void cacheSuggestions(String prefix, List<SearchSuggestion> suggestions, Duration ttl) {
        String normalized = normalize(prefix);
        if (normalized.isBlank() || suggestions == null || suggestions.isEmpty()) {
            return;
        }
        fallbackSuggestions.put(normalized, suggestions);
        if (redisTemplate == null) {
            return;
        }
        String key = suggestionKey(normalized);
        try {
            for (SearchSuggestion suggestion : suggestions) {
                redisTemplate.opsForZSet().add(key, suggestion.keyword(), suggestion.score());
            }
            redisTemplate.expire(key, ttl);
        } catch (RuntimeException ignored) {
            // Fallback cache has already been written.
        }
    }

    @Override
    public void refreshHotKeywordSnapshot() {
        fallbackSnapshot = hotKeywords(10);
        if (redisTemplate == null) {
            return;
        }
        try {
            redisTemplate.delete(SNAPSHOT_KEY);
            for (HotKeyword keyword : fallbackSnapshot) {
                redisTemplate.opsForZSet().add(SNAPSHOT_KEY, keyword.keyword(), keyword.score());
            }
            redisTemplate.expire(SNAPSHOT_KEY, Duration.ofMinutes(5));
        } catch (RuntimeException ignored) {
            // Snapshot remains available in memory.
        }
    }

    private List<HotKeyword> fallbackHotKeywords(int limit) {
        List<HotKeyword> values = fallbackHotKeywords.entrySet().stream()
                .map(entry -> new HotKeyword(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(HotKeyword::score).reversed())
                .limit(Math.max(1, limit))
                .toList();
        return values.isEmpty()
                ? fallbackSnapshot.stream().limit(Math.max(1, limit)).toList()
                : values;
    }

    private List<SearchSuggestion> fallbackSuggestions(String prefix, int limit) {
        return fallbackSuggestions.getOrDefault(prefix, List.of()).stream()
                .limit(Math.max(1, limit))
                .toList();
    }

    private static String suggestionKey(String prefix) {
        return SUGGEST_PREFIX + prefix;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
