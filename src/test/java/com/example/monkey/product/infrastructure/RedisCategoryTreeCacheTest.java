package com.example.monkey.product.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.monkey.product.domain.CategoryNode;
import com.example.monkey.shared.application.tenant.TenantContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RedisCategoryTreeCacheTest {

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void categoryTreesWithTheSameShapeRemainIsolatedByTenant() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        Map<String, String> valuesByKey = new HashMap<>();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString()))
                .thenAnswer(invocation -> valuesByKey.get(invocation.getArgument(0)));
        doAnswer(invocation -> {
                    valuesByKey.put(invocation.getArgument(0), invocation.getArgument(1));
                    return null;
                })
                .when(valueOperations)
                .set(anyString(), anyString(), any(Duration.class));
        when(redisTemplate.delete(anyString()))
                .thenAnswer(invocation -> valuesByKey.remove(invocation.getArgument(0)) != null);

        ObjectMapper objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        RedisCategoryTreeCache cache = new RedisCategoryTreeCache(redisTemplate, objectMapper);
        CategoryNode tenantOneNode = new CategoryNode(101L, null, 1, "tenant-one", "Tenant One", List.of());
        CategoryNode tenantTwoNode = new CategoryNode(202L, null, 1, "tenant-two", "Tenant Two", List.of());

        TenantContext.setTenantId(1L);
        cache.put(List.of(tenantOneNode));
        TenantContext.setTenantId(2L);
        assertThat(cache.get()).isEmpty();
        cache.put(List.of(tenantTwoNode));

        TenantContext.setTenantId(1L);
        assertThat(cache.get()).hasValue(List.of(tenantOneNode));
        TenantContext.setTenantId(2L);
        assertThat(cache.get()).hasValue(List.of(tenantTwoNode));
        assertThat(valuesByKey).containsKeys(
                "catalog:category-tree:v1:tenant:1", "catalog:category-tree:v1:tenant:2");
    }
}
