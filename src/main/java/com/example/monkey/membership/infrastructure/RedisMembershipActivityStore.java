package com.example.monkey.membership.infrastructure;

import com.example.monkey.membership.domain.BrowseHistoryItem;
import com.example.monkey.membership.domain.MembershipActivityStore;
import com.example.monkey.shared.application.tenant.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisMembershipActivityStore implements MembershipActivityStore {

    private static final String KEY_PREFIX = "membership:browse:tenant:";
    private static final int DEFAULT_REFERENCE_SCAN_BATCH_SIZE = 500;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final int referenceScanBatchSize;
    private final Map<HistoryIdentity, Map<Long, BrowseHistoryItem>> fallback = new ConcurrentHashMap<>();

    public RedisMembershipActivityStore(
            ObjectProvider<StringRedisTemplate> redisTemplateProvider, ObjectMapper objectMapper) {
        this(redisTemplateProvider, objectMapper, DEFAULT_REFERENCE_SCAN_BATCH_SIZE);
    }

    @Autowired
    public RedisMembershipActivityStore(
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            ObjectMapper objectMapper,
            @Value("${app.upload.cleanup.reference-scan-batch-size:500}") int referenceScanBatchSize) {
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
        this.objectMapper = objectMapper;
        this.referenceScanBatchSize = Math.max(1, referenceScanBatchSize);
    }

    @Override
    public BrowseHistoryItem record(BrowseHistoryItem item, Duration ttl) {
        long tenantId = TenantContext.currentTenantIdOrDefault();
        HistoryIdentity identity = new HistoryIdentity(tenantId, item.userId());
        if (redisTemplate == null) {
            recordFallback(identity, item);
            return item;
        }
        String key = key(tenantId, item.userId());
        try {
            removeProduct(key, item.productId());
            redisTemplate.opsForZSet().add(key, serialize(item), score(item.viewedAt()));
            redisTemplate.expire(key, ttl);
            removeFallback(identity, item.productId());
        } catch (RuntimeException exception) {
            recordFallback(identity, item);
        }
        return item;
    }

    @Override
    public List<BrowseHistoryItem> findRecent(Long userId, int limit) {
        long tenantId = TenantContext.currentTenantIdOrDefault();
        HistoryIdentity identity = new HistoryIdentity(tenantId, userId);
        if (redisTemplate == null) {
            return fallbackRecent(identity, limit);
        }
        try {
            Set<String> values =
                    redisTemplate.opsForZSet().reverseRange(key(tenantId, userId), 0, Math.max(0, limit - 1));
            if (values == null) {
                return List.of();
            }
            return values.stream().map(this::deserialize).toList();
        } catch (RuntimeException exception) {
            return fallbackRecent(identity, limit);
        }
    }

    /**
     * Enumerates every live browse-history row owned by the current tenant.
     *
     * <p>This path is deliberately stricter than {@link #findRecent(Long, int)}. The dashboard read can
     * fall back to an in-memory value when Redis is unavailable, but cleanup must never publish a partial
     * snapshot after a Redis scan, range read, or deserialization failure. When Redis is configured and a
     * write previously fell back locally, those locally retained rows are merged after a successful Redis
     * read so a transient write failure cannot make its image eligible for deletion.
     */
    public void forEachLiveBrowseHistoryItem(Consumer<BrowseHistoryItem> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        long tenantId = TenantContext.currentTenantIdOrDefault();
        LocalDateTime now = LocalDateTime.now();
        Set<HistoryItemIdentity> redisIdentities = ConcurrentHashMap.newKeySet();
        if (redisTemplate == null) {
            emitFallback(tenantId, now, consumer, redisIdentities);
            return;
        }

        String tenantKeyPrefix = keyPrefix(tenantId);
        ScanOptions scanOptions = ScanOptions.scanOptions()
                .match(tenantKeyPrefix + "*")
                .count(referenceScanBatchSize)
                .build();
        try (Cursor<String> keys = redisTemplate.scan(scanOptions)) {
            if (keys == null) {
                throw new IllegalStateException("Redis returned no browse history key cursor");
            }
            while (keys.hasNext()) {
                String redisKey = keys.next();
                long userId = parseUserId(redisKey, tenantKeyPrefix);
                Set<String> values = redisTemplate.opsForZSet().range(redisKey, 0L, -1L);
                if (values == null) {
                    throw new IllegalStateException("Redis returned no browse history values for " + redisKey);
                }
                for (String value : values) {
                    BrowseHistoryItem item = deserialize(value);
                    validateItemBelongsToUser(item, userId);
                    if (!isLive(item, now)) {
                        continue;
                    }
                    redisIdentities.add(new HistoryItemIdentity(tenantId, item.userId(), item.productId()));
                    consumer.accept(item);
                }
            }
        }
        emitFallback(tenantId, now, consumer, redisIdentities);
    }

    private void recordFallback(HistoryIdentity identity, BrowseHistoryItem item) {
        fallback.computeIfAbsent(identity, ignored -> new ConcurrentHashMap<>()).put(item.productId(), item);
    }

    private void removeFallback(HistoryIdentity identity, Long productId) {
        Map<Long, BrowseHistoryItem> items = fallback.get(identity);
        if (items == null) {
            return;
        }
        items.remove(productId);
        if (items.isEmpty()) {
            fallback.remove(identity, items);
        }
    }

    private void emitFallback(
            long tenantId,
            LocalDateTime now,
            Consumer<BrowseHistoryItem> consumer,
            Set<HistoryItemIdentity> alreadyReadFromRedis) {
        fallback.forEach((identity, items) -> {
            if (identity.tenantId() != tenantId) {
                return;
            }
            items.forEach((productId, item) -> {
                if (!isLive(item, now)) {
                    return;
                }
                HistoryItemIdentity itemIdentity = new HistoryItemIdentity(tenantId, identity.userId(), productId);
                if (alreadyReadFromRedis.add(itemIdentity)) {
                    consumer.accept(item);
                }
            });
        });
    }

    private List<BrowseHistoryItem> fallbackRecent(HistoryIdentity identity, int limit) {
        LocalDateTime now = LocalDateTime.now();
        return fallback.computeIfAbsent(identity, ignored -> new ConcurrentHashMap<>()).values().stream()
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

    private static boolean isLive(BrowseHistoryItem item, LocalDateTime now) {
        if (item == null || item.userId() == null || item.productId() == null) {
            throw new IllegalStateException("Redis returned an incomplete browse history item");
        }
        return item.expiresAt() == null || item.expiresAt().isAfter(now);
    }

    private static void validateItemBelongsToUser(BrowseHistoryItem item, long userId) {
        if (item == null || item.userId() == null || item.userId() != userId) {
            throw new IllegalStateException("Redis browse history item is outside its tenant user key");
        }
    }

    private static long parseUserId(String redisKey, String tenantKeyPrefix) {
        if (redisKey == null || !redisKey.startsWith(tenantKeyPrefix)) {
            throw new IllegalStateException("Redis returned a browse history key outside the current tenant");
        }
        String userPart = redisKey.substring(tenantKeyPrefix.length());
        if (userPart.isBlank() || userPart.contains(":")) {
            throw new IllegalStateException("Redis returned an invalid browse history user key");
        }
        try {
            long userId = Long.parseLong(userPart);
            if (userId <= 0L) {
                throw new IllegalStateException("Redis returned an invalid browse history user key");
            }
            return userId;
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("Redis returned an invalid browse history user key", exception);
        }
    }

    private static double score(LocalDateTime viewedAt) {
        return viewedAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private static String key(long tenantId, Long userId) {
        return KEY_PREFIX + tenantId + ":user:" + userId;
    }

    private static String keyPrefix(long tenantId) {
        return KEY_PREFIX + tenantId + ":user:";
    }

    private record HistoryIdentity(long tenantId, Long userId) {}

    private record HistoryItemIdentity(long tenantId, Long userId, Long productId) {}
}
