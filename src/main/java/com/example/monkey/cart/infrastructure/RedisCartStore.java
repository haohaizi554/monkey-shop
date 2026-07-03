package com.example.monkey.cart.infrastructure;

import com.example.monkey.cart.domain.CartItem;
import com.example.monkey.cart.domain.CartSnapshot;
import com.example.monkey.cart.domain.CartStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisCartStore implements CartStore {

    private static final String KEY_PREFIX = "cart:user:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Map<Long, Map<String, String>> fallback = new ConcurrentHashMap<>();

    public RedisCartStore(ObjectProvider<StringRedisTemplate> redisTemplateProvider, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
        this.objectMapper = objectMapper;
    }

    @Override
    public CartSnapshot findCart(Long userId) {
        Map<String, String> values = redisTemplate == null ? fallbackCart(userId) : redisCart(userId);
        return new CartSnapshot(
                userId, values.values().stream().map(this::deserialize).toList());
    }

    @Override
    public CartSnapshot save(CartSnapshot cart, Duration ttl) {
        Map<String, String> values = new LinkedHashMap<>();
        for (CartItem item : cart.items()) {
            values.put(Long.toString(item.skuId()), serialize(item));
        }
        if (redisTemplate == null) {
            fallback.put(cart.userId(), values);
            return cart;
        }
        String key = key(cart.userId());
        redisTemplate.delete(key);
        if (!values.isEmpty()) {
            redisTemplate.opsForHash().putAll(key, values);
            redisTemplate.expire(key, ttl);
        }
        return cart;
    }

    @Override
    public void removeItems(Long userId, List<Long> skuIds, Duration ttl) {
        if (skuIds == null || skuIds.isEmpty()) {
            return;
        }
        Object[] fields = skuIds.stream().map(String::valueOf).toArray();
        if (redisTemplate == null) {
            Map<String, String> cart = fallbackCart(userId);
            for (Object field : fields) {
                cart.remove(field.toString());
            }
            return;
        }
        redisTemplate.opsForHash().delete(key(userId), fields);
        redisTemplate.expire(key(userId), ttl);
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
        return fallback.computeIfAbsent(userId, ignored -> new ConcurrentHashMap<>());
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

    private record StoredCartItem(
            Long skuId, Long shopId, int quantity, boolean selected, LocalDateTime addedAt, LocalDateTime updatedAt) {}
}
