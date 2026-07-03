package com.example.monkey.product.infrastructure;

import com.example.monkey.product.domain.CategoryNode;
import com.example.monkey.product.domain.CategoryTreeCache;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@ConditionalOnProperty(name = "app.catalog.category-cache.provider", havingValue = "redis", matchIfMissing = true)
public class RedisCategoryTreeCache implements CategoryTreeCache {

    static final String CACHE_KEY = "catalog:category-tree:v1";
    static final Duration CACHE_TTL = Duration.ofHours(1);

    private static final TypeReference<List<CategoryNode>> CATEGORY_TREE_TYPE = new TypeReference<>() {};

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisCategoryTreeCache(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<List<CategoryNode>> get() {
        String cached = redisTemplate.opsForValue().get(CACHE_KEY);
        if (!StringUtils.hasText(cached)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(cached, CATEGORY_TREE_TYPE));
        } catch (JsonProcessingException exception) {
            evict();
            return Optional.empty();
        }
    }

    @Override
    public void put(List<CategoryNode> categoryTree) {
        try {
            redisTemplate.opsForValue().set(CACHE_KEY, objectMapper.writeValueAsString(categoryTree), CACHE_TTL);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Category tree cannot be serialized", exception);
        }
    }

    @Override
    public void evict() {
        redisTemplate.delete(CACHE_KEY);
    }
}
