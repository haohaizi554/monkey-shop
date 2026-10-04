package com.example.monkey.membership.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.membership.domain.BrowseHistoryItem;
import com.example.monkey.shared.application.tenant.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.lang.NonNull;

class RedisMembershipActivityStoreTest {

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void recordKeepsRecentBrowseHistoryWithFallbackTtl() {
        RedisMembershipActivityStore store =
                new RedisMembershipActivityStore(noRedis(), new ObjectMapper().findAndRegisterModules());
        LocalDateTime now = LocalDateTime.now().minusMinutes(3);

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

    @Test
    void fallbackBrowseHistoryScopesSameUserAndProductByTenant() {
        RedisMembershipActivityStore store =
                new RedisMembershipActivityStore(noRedis(), new ObjectMapper().findAndRegisterModules());
        LocalDateTime now = LocalDateTime.now().minusMinutes(3);
        BrowseHistoryItem tenantOneItem =
                new BrowseHistoryItem(1L, 9L, 101L, "Tenant one", null, now, now.plusDays(7));
        BrowseHistoryItem tenantTwoItem =
                new BrowseHistoryItem(2L, 9L, 101L, "Tenant two", null, now.plusMinutes(1), now.plusDays(7));

        TenantContext.setTenantId(1L);
        store.record(tenantOneItem, Duration.ofDays(7));
        TenantContext.setTenantId(2L);
        store.record(tenantTwoItem, Duration.ofDays(7));

        TenantContext.setTenantId(1L);
        assertThat(store.findRecent(9L, 10)).containsExactly(tenantOneItem);
        TenantContext.setTenantId(2L);
        assertThat(store.findRecent(9L, 10)).containsExactly(tenantTwoItem);
    }

    @Test
    void redisBrowseHistoryKeysIncludeTenantForRecordAndRead() {
        ObjectProvider<StringRedisTemplate> provider = mock();
        StringRedisTemplate redisTemplate = mock();
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> zSetOperations = mock();
        when(provider.getIfAvailable()).thenReturn(redisTemplate);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.range(anyString(), eq(0L), eq(-1L))).thenReturn(Set.of());
        when(zSetOperations.add(anyString(), anyString(), anyDouble())).thenReturn(true);
        when(zSetOperations.reverseRange(anyString(), eq(0L), eq(0L))).thenReturn(Set.of());
        when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(true);
        RedisMembershipActivityStore store =
                new RedisMembershipActivityStore(provider, new ObjectMapper().findAndRegisterModules());
        LocalDateTime now = LocalDateTime.now().minusMinutes(3);
        Duration ttl = Duration.ofDays(7);

        TenantContext.setTenantId(1L);
        store.record(new BrowseHistoryItem(1L, 9L, 101L, "Tenant one", null, now, now.plusDays(7)), ttl);
        assertThat(store.findRecent(9L, 1)).isEmpty();
        TenantContext.setTenantId(2L);
        store.record(new BrowseHistoryItem(2L, 9L, 101L, "Tenant two", null, now, now.plusDays(7)), ttl);
        assertThat(store.findRecent(9L, 1)).isEmpty();

        String tenantOneKey = "membership:browse:tenant:1:user:9";
        String tenantTwoKey = "membership:browse:tenant:2:user:9";
        ArgumentCaptor<String> recordRangeKeys = ArgumentCaptor.forClass(String.class);
        verify(zSetOperations, times(2)).range(recordRangeKeys.capture(), eq(0L), eq(-1L));
        assertThat(recordRangeKeys.getAllValues()).containsExactly(tenantOneKey, tenantTwoKey);

        ArgumentCaptor<String> addKeys = ArgumentCaptor.forClass(String.class);
        verify(zSetOperations, times(2)).add(addKeys.capture(), anyString(), anyDouble());
        assertThat(addKeys.getAllValues()).containsExactly(tenantOneKey, tenantTwoKey);

        ArgumentCaptor<String> readKeys = ArgumentCaptor.forClass(String.class);
        verify(zSetOperations, times(2)).reverseRange(readKeys.capture(), eq(0L), eq(0L));
        assertThat(readKeys.getAllValues()).containsExactly(tenantOneKey, tenantTwoKey);

        ArgumentCaptor<String> expiryKeys = ArgumentCaptor.forClass(String.class);
        verify(redisTemplate, times(2)).expire(expiryKeys.capture(), eq(ttl));
        assertThat(expiryKeys.getAllValues()).containsExactly(tenantOneKey, tenantTwoKey);
    }

    @Test
    void redisErrorFallbackUsesTenantCapturedBeforeRedisOperation() {
        ObjectProvider<StringRedisTemplate> provider = mock();
        StringRedisTemplate redisTemplate = mock();
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> zSetOperations = mock();
        when(provider.getIfAvailable()).thenReturn(redisTemplate);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.range(anyString(), eq(0L), eq(-1L))).thenAnswer(invocation -> {
            TenantContext.setTenantId(2L);
            throw new IllegalStateException("Redis unavailable");
        });
        when(zSetOperations.reverseRange(anyString(), eq(0L), eq(9L))).thenAnswer(invocation -> {
            TenantContext.setTenantId(2L);
            throw new IllegalStateException("Redis unavailable");
        });
        RedisMembershipActivityStore store =
                new RedisMembershipActivityStore(provider, new ObjectMapper().findAndRegisterModules());
        LocalDateTime now = LocalDateTime.now().minusMinutes(3);
        BrowseHistoryItem tenantOneItem =
                new BrowseHistoryItem(1L, 9L, 101L, "Tenant one", null, now, now.plusDays(7));

        TenantContext.setTenantId(1L);
        store.record(tenantOneItem, Duration.ofDays(7));

        TenantContext.setTenantId(1L);
        assertThat(store.findRecent(9L, 10)).containsExactly(tenantOneItem);
        TenantContext.setTenantId(2L);
        assertThat(store.findRecent(9L, 10)).isEmpty();
    }

    private static ObjectProvider<StringRedisTemplate> noRedis() {
        return new ObjectProvider<>() {
            @Override
            public StringRedisTemplate getObject(@NonNull Object... args) {
                throw new NoSuchBeanDefinitionException(StringRedisTemplate.class);
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
                throw new NoSuchBeanDefinitionException(StringRedisTemplate.class);
            }
        };
    }
}
