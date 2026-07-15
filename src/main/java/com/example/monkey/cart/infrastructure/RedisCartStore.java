package com.example.monkey.cart.infrastructure;

import com.example.monkey.cart.domain.CartItem;
import com.example.monkey.cart.domain.CartSnapshot;
import com.example.monkey.cart.domain.CartStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class RedisCartStore implements CartStore {

    private static final String KEY_PREFIX = "cart:user:";
    private static final DefaultRedisScript<Long> PUT_ITEM_SCRIPT = new DefaultRedisScript<>("""
            redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
            redis.call('EXPIRE', KEYS[1], ARGV[3])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> REMOVE_ITEM_SCRIPT = new DefaultRedisScript<>("""
            local deleted = redis.call('HDEL', KEYS[1], ARGV[1])
            if redis.call('EXISTS', KEYS[1]) == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[2])
            end
            return deleted
            """, Long.class);
    private static final DefaultRedisScript<Long> REMOVE_MATCHING_ITEMS_SCRIPT =
            new DefaultRedisScript<>("""
                    local deleted = 0
                    for index = 1, #ARGV - 1, 2 do
                        if redis.call('HGET', KEYS[1], ARGV[index]) == ARGV[index + 1] then
                            deleted = deleted + redis.call('HDEL', KEYS[1], ARGV[index])
                        end
                    end
                    if redis.call('EXISTS', KEYS[1]) == 1 then
                        redis.call('EXPIRE', KEYS[1], ARGV[#ARGV])
                    end
                    return deleted
                    """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Map<Long, FallbackCartState> fallback = new ConcurrentHashMap<>();

    @Autowired
    public RedisCartStore(ObjectProvider<StringRedisTemplate> redisTemplateProvider, ObjectMapper objectMapper) {
        this(redisTemplateProvider, objectMapper, Clock.systemUTC());
    }

    RedisCartStore(ObjectProvider<StringRedisTemplate> redisTemplateProvider, ObjectMapper objectMapper, Clock clock) {
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public CartSnapshot findCart(Long userId) {
        Map<String, String> values = redisTemplate == null ? fallbackCart(userId) : redisCart(userId);
        return new CartSnapshot(
                userId, values.values().stream().map(this::deserialize).toList());
    }

    @Override
    public void putItem(Long userId, CartItem item, Duration ttl) {
        String field = item.skuId().toString();
        String value = serialize(item);
        if (redisTemplate == null) {
            putFallbackItem(userId, field, value, ttl);
            return;
        }
        redisTemplate.execute(PUT_ITEM_SCRIPT, List.of(key(userId)), field, value, Long.toString(ttlSeconds(ttl)));
    }

    @Override
    public void removeItem(Long userId, Long skuId, Duration ttl) {
        if (redisTemplate == null) {
            removeFallbackItem(userId, skuId.toString(), ttl);
            return;
        }
        redisTemplate.execute(
                REMOVE_ITEM_SCRIPT, List.of(key(userId)), skuId.toString(), Long.toString(ttlSeconds(ttl)));
    }

    @Override
    public void removeMatchingItems(Long userId, List<CartItem> expectedItems, Duration ttl) {
        if (expectedItems == null || expectedItems.isEmpty()) {
            return;
        }
        if (redisTemplate == null) {
            removeMatchingFallbackItems(userId, expectedItems, ttl);
            return;
        }
        List<Object> arguments = new ArrayList<>(expectedItems.size() * 2 + 1);
        expectedItems.forEach(item -> {
            arguments.add(item.skuId().toString());
            arguments.add(serialize(item));
        });
        arguments.add(Long.toString(ttlSeconds(ttl)));
        redisTemplate.execute(REMOVE_MATCHING_ITEMS_SCRIPT, List.of(key(userId)), arguments.toArray());
    }

    private Map<String, String> redisCart(Long userId) {
        try {
            Map<Object, Object> entries = redisTemplate.opsForHash().entries(key(userId));
            Map<String, String> values = new LinkedHashMap<>();
            entries.forEach((field, value) -> values.put(String.valueOf(field), String.valueOf(value)));
            return values;
        } catch (RuntimeException exception) {
            return fallbackCart(userId);
        }
    }

    private Map<String, String> fallbackCart(Long userId) {
        FallbackCartState state = fallback.compute(
                userId, (ignored, current) -> current == null || current.expiredAt(clock.instant()) ? null : current);
        return state == null ? Map.of() : state.items();
    }

    private void putFallbackItem(Long userId, String field, String value, Duration ttl) {
        Instant now = clock.instant();
        fallback.compute(userId, (ignored, current) -> {
            Map<String, String> items = mutableItems(current, now);
            items.put(field, value);
            return new FallbackCartState(Map.copyOf(items), now.plusSeconds(ttlSeconds(ttl)));
        });
    }

    private void removeFallbackItem(Long userId, String field, Duration ttl) {
        Instant now = clock.instant();
        fallback.compute(userId, (ignored, current) -> {
            Map<String, String> items = mutableItems(current, now);
            items.remove(field);
            return items.isEmpty() ? null : new FallbackCartState(Map.copyOf(items), now.plusSeconds(ttlSeconds(ttl)));
        });
    }

    private void removeMatchingFallbackItems(Long userId, List<CartItem> expectedItems, Duration ttl) {
        Map<String, String> expectedValues = new LinkedHashMap<>();
        expectedItems.forEach(item -> expectedValues.put(item.skuId().toString(), serialize(item)));
        Instant now = clock.instant();
        fallback.compute(userId, (ignored, current) -> {
            Map<String, String> items = mutableItems(current, now);
            expectedValues.forEach((field, expectedValue) ->
                    items.compute(field, (key, value) -> Objects.equals(value, expectedValue) ? null : value));
            return items.isEmpty() ? null : new FallbackCartState(Map.copyOf(items), now.plusSeconds(ttlSeconds(ttl)));
        });
    }

    private static Map<String, String> mutableItems(FallbackCartState current, Instant now) {
        return current == null || current.expiredAt(now) ? new LinkedHashMap<>() : new LinkedHashMap<>(current.items());
    }

    private static long ttlSeconds(Duration ttl) {
        return Math.max(1, ttl.toSeconds());
    }

    private String serialize(CartItem item) {
        try {
            return objectMapper.writeValueAsString(new StoredCartItem(
                    item.skuId(), item.shopId(), item.quantity(), item.selected(), item.addedAt(), item.updatedAt()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cart item cannot be serialized", exception);
        }
    }

    private CartItem deserialize(String value) {
        try {
            StoredCartItem item = objectMapper.readValue(value, StoredCartItem.class);
            return new CartItem(
                    item.skuId(), item.shopId(), item.quantity(), item.selected(), item.addedAt(), item.updatedAt());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cart item cannot be deserialized", exception);
        }
    }

    private static String key(Long userId) {
        return KEY_PREFIX + userId;
    }

    private record FallbackCartState(Map<String, String> items, Instant expiresAt) {

        private boolean expiredAt(Instant instant) {
            return !expiresAt.isAfter(instant);
        }
    }

    private record StoredCartItem(
            Long skuId, Long shopId, int quantity, boolean selected, LocalDateTime addedAt, LocalDateTime updatedAt) {}
}
