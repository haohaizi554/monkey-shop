package com.example.monkey.search.infrastructure;

import com.example.monkey.search.domain.HotKeyword;
import com.example.monkey.search.domain.SearchActivityStore;
import com.example.monkey.search.domain.SearchSuggestion;
import com.example.monkey.shared.application.tenant.TenantContext;
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
    private final Map<Long, Map<String, Long>> fallbackHotKeywords = new ConcurrentHashMap<>();
    private final Map<Long, Map<String, List<SearchSuggestion>>> fallbackSuggestions = new ConcurrentHashMap<>();
    private final Map<Long, List<HotKeyword>> fallbackSnapshots = new ConcurrentHashMap<>();

    public RedisSearchActivityStore(ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
    }

    @Override
    public void recordKeyword(String keyword) {
        String normalized = normalize(keyword);
        if (normalized.isBlank()) {
            return;
        }
        long tenantId = TenantContext.currentTenantIdOrDefault();
        if (redisTemplate == null) {
            recordFallbackKeyword(tenantId, normalized);
            return;
        }
        try {
            redisTemplate.opsForZSet().incrementScore(hotKey(tenantId), normalized, 1);
        } catch (RuntimeException exception) {
            recordFallbackKeyword(tenantId, normalized);
        }
    }

    @Override
    public List<HotKeyword> hotKeywords(int limit) {
        return hotKeywords(TenantContext.currentTenantIdOrDefault(), limit);
    }

    private List<HotKeyword> hotKeywords(long tenantId, int limit) {
        if (redisTemplate == null) {
            return fallbackHotKeywords(tenantId, limit);
        }
        try {
            Set<ZSetOperations.TypedTuple<String>> tuples =
                    redisTemplate.opsForZSet().reverseRangeWithScores(hotKey(tenantId), 0, Math.max(0, limit - 1));
            if (tuples == null || tuples.isEmpty()) {
                return fallbackHotKeywords(tenantId, limit);
            }
            return tuples.stream()
                    .map(tuple -> new HotKeyword(
                            tuple.getValue(), Math.round(tuple.getScore() == null ? 0 : tuple.getScore())))
                    .toList();
        } catch (RuntimeException exception) {
            return fallbackHotKeywords(tenantId, limit);
        }
    }

    @Override
    public List<SearchSuggestion> suggestions(String prefix, int limit) {
        String normalized = normalize(prefix);
        long tenantId = TenantContext.currentTenantIdOrDefault();
        if (redisTemplate == null) {
            return fallbackSuggestions(tenantId, normalized, limit);
        }
        try {
            Set<ZSetOperations.TypedTuple<String>> tuples = redisTemplate
                    .opsForZSet()
                    .reverseRangeWithScores(suggestionKey(tenantId, normalized), 0, Math.max(0, limit - 1));
            if (tuples == null || tuples.isEmpty()) {
                return fallbackSuggestions(tenantId, normalized, limit);
            }
            return tuples.stream()
                    .map(tuple -> new SearchSuggestion(
                            tuple.getValue(), "cache", Math.round(tuple.getScore() == null ? 0 : tuple.getScore())))
                    .toList();
        } catch (RuntimeException exception) {
            return fallbackSuggestions(tenantId, normalized, limit);
        }
    }

    @Override
    public void cacheSuggestions(String prefix, List<SearchSuggestion> suggestions, Duration ttl) {
        String normalized = normalize(prefix);
        if (normalized.isBlank() || suggestions == null || suggestions.isEmpty()) {
            return;
        }
        long tenantId = TenantContext.currentTenantIdOrDefault();
        fallbackSuggestions
                .computeIfAbsent(tenantId, ignored -> new ConcurrentHashMap<>())
                .put(normalized, suggestions);
        if (redisTemplate == null) {
            return;
        }
        String key = suggestionKey(tenantId, normalized);
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
        long tenantId = TenantContext.currentTenantIdOrDefault();
        List<HotKeyword> snapshot = hotKeywords(tenantId, 10);
        fallbackSnapshots.put(tenantId, snapshot);
        if (redisTemplate == null) {
            return;
        }
        try {
            String key = snapshotKey(tenantId);
            redisTemplate.delete(key);
            for (HotKeyword keyword : snapshot) {
                redisTemplate.opsForZSet().add(key, keyword.keyword(), keyword.score());
            }
            redisTemplate.expire(key, Duration.ofMinutes(5));
        } catch (RuntimeException ignored) {
            // Snapshot remains available in memory.
        }
    }

    private void recordFallbackKeyword(long tenantId, String keyword) {
        fallbackHotKeywords
                .computeIfAbsent(tenantId, ignored -> new ConcurrentHashMap<>())
                .merge(keyword, 1L, Long::sum);
    }

    private List<HotKeyword> fallbackHotKeywords(long tenantId, int limit) {
        List<HotKeyword> values = fallbackHotKeywords.getOrDefault(tenantId, Map.of()).entrySet().stream()
                .map(entry -> new HotKeyword(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(HotKeyword::score).reversed())
                .limit(Math.max(1, limit))
                .toList();
        return values.isEmpty()
                ? fallbackSnapshots.getOrDefault(tenantId, List.of()).stream().limit(Math.max(1, limit)).toList()
                : values;
    }

    private List<SearchSuggestion> fallbackSuggestions(long tenantId, String prefix, int limit) {
        return fallbackSuggestions.getOrDefault(tenantId, Map.of()).getOrDefault(prefix, List.of()).stream()
                .limit(Math.max(1, limit))
                .toList();
    }

    private static String hotKey(long tenantId) {
        return HOT_KEY + ":tenant:" + tenantId;
    }

    private static String suggestionKey(long tenantId, String prefix) {
        return SUGGEST_PREFIX + "tenant:" + tenantId + ":" + prefix;
    }

    private static String snapshotKey(long tenantId) {
        return SNAPSHOT_KEY + ":tenant:" + tenantId;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
