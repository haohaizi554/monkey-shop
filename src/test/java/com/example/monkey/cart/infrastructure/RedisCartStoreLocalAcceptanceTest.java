package com.example.monkey.cart.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.monkey.cart.domain.CartItem;
import com.example.monkey.shared.application.tenant.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

class RedisCartStoreLocalAcceptanceTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_CART_REDIS_ACCEPTANCE", matches = "true")
    void luaCleanupConditionallyDeletesAndRefreshesTtlOnOneIsolatedKey() {
        String host = System.getenv().getOrDefault("CART_REDIS_HOST", "127.0.0.1");
        int port = Integer.parseInt(System.getenv().getOrDefault("CART_REDIS_PORT", "6379"));
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(host, port);
        JedisConnectionFactory connectionFactory = new JedisConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        StringRedisTemplate redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        ObjectProvider<StringRedisTemplate> provider = mock();
        when(provider.getIfAvailable()).thenReturn(redisTemplate);
        RedisCartStore store = new RedisCartStore(provider, new ObjectMapper().findAndRegisterModules());
        Long userId = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
        String key = "cart:tenant:1:user:" + userId;
        LocalDateTime now = LocalDateTime.parse("2026-01-01T00:00:00");
        CartItem checkoutSnapshot = new CartItem(1001L, 501L, 2, true, now, now);
        CartItem changed = new CartItem(1001L, 501L, 3, true, now, now.plusMinutes(1));
        CartItem unchanged = new CartItem(1002L, 501L, 1, true, now, now);
        try {
            TenantContext.setTenantId(1L);
            store.putItem(userId, changed, Duration.ofMinutes(5));
            store.putItem(userId, unchanged, Duration.ofMinutes(5));

            store.removeMatchingItems(userId, List.of(checkoutSnapshot, unchanged), Duration.ofMinutes(10));

            assertThat(store.findCart(userId).items()).containsExactly(changed);
            assertThat(redisTemplate.getExpire(key, TimeUnit.SECONDS)).isBetween(500L, 600L);
        } finally {
            TenantContext.clear();
            redisTemplate.delete(key);
            connectionFactory.destroy();
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_CART_REDIS_ACCEPTANCE", matches = "true")
    void staleCasAfterCleanupCannotResurrectRemovedItems() {
        String host = System.getenv().getOrDefault("CART_REDIS_HOST", "127.0.0.1");
        int port = Integer.parseInt(System.getenv().getOrDefault("CART_REDIS_PORT", "6379"));
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(host, port);
        JedisConnectionFactory connectionFactory = new JedisConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        StringRedisTemplate redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        ObjectProvider<StringRedisTemplate> provider = mock();
        when(provider.getIfAvailable()).thenReturn(redisTemplate);
        RedisCartStore store = new RedisCartStore(provider, new ObjectMapper().findAndRegisterModules());
        Long userId = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
        String key = "cart:tenant:1:user:" + userId;
        Duration ttl = Duration.ofMinutes(10);
        LocalDateTime now = LocalDateTime.parse("2026-01-01T00:00:00");
        LocalDateTime later = now.plusMinutes(1);
        List<CartItem> checkoutSnapshots = List.of(
                new CartItem(1001L, 501L, 2, true, now, now),
                new CartItem(1002L, 501L, 1, true, now, now),
                new CartItem(1003L, 501L, 1, true, now, now),
                new CartItem(1004L, 501L, 1, true, now, now));
        try {
            TenantContext.setTenantId(1L);
            checkoutSnapshots.forEach(item -> store.putItem(userId, item, ttl));
            var staleMutationRead = store.findCart(userId);

            store.removeMatchingItems(userId, checkoutSnapshots, ttl);
            CartItem staleQuantity = staleMutationRead.items().stream()
                    .filter(item -> item.skuId().equals(1001L))
                    .findFirst()
                    .orElseThrow();
            CartItem quantityChanged = staleQuantity.withQuantity(3, later);
            CartItem staleSelection = staleMutationRead.items().stream()
                    .filter(item -> item.skuId().equals(1002L))
                    .findFirst()
                    .orElseThrow();
            CartItem unselected = staleSelection.select(false, later);
            CartItem readded = new CartItem(1003L, 501L, 1, true, later, later);
            CartItem brandNew = new CartItem(2001L, 502L, 1, true, later, later);

            assertThat(store.putItemIfUnchanged(userId, staleQuantity, quantityChanged, ttl))
                    .isFalse();
            assertThat(store.putItemIfUnchanged(userId, staleSelection, unselected, ttl))
                    .isFalse();
            assertThat(store.putItemIfUnchanged(userId, null, readded, ttl)).isTrue();
            assertThat(store.putItemIfUnchanged(userId, null, brandNew, ttl)).isTrue();

            assertThat(store.findCart(userId).items()).containsExactlyInAnyOrder(readded, brandNew);
            assertThat(store.findCart(userId).items())
                    .noneMatch(item -> List.of(1001L, 1002L, 1004L).contains(item.skuId()));
        } finally {
            TenantContext.clear();
            redisTemplate.delete(key);
            connectionFactory.destroy();
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_CART_REDIS_ACCEPTANCE", matches = "true")
    void staleCasCannotOverwriteNewerRedisItem() {
        String host = System.getenv().getOrDefault("CART_REDIS_HOST", "127.0.0.1");
        int port = Integer.parseInt(System.getenv().getOrDefault("CART_REDIS_PORT", "6379"));
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(host, port);
        JedisConnectionFactory connectionFactory = new JedisConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        StringRedisTemplate redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        ObjectProvider<StringRedisTemplate> provider = mock();
        when(provider.getIfAvailable()).thenReturn(redisTemplate);
        RedisCartStore store = new RedisCartStore(provider, new ObjectMapper().findAndRegisterModules());
        Long userId = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
        String key = "cart:tenant:1:user:" + userId;
        Duration ttl = Duration.ofMinutes(10);
        LocalDateTime now = LocalDateTime.parse("2026-01-01T00:00:00");
        CartItem initial = new CartItem(1001L, 501L, 1, true, now, now);
        CartItem newer = initial.withQuantity(2, now.plusSeconds(1));
        CartItem staleOverwrite = initial.select(false, now.plusSeconds(2));
        try {
            TenantContext.setTenantId(1L);
            store.putItem(userId, initial, ttl);

            assertThat(store.putItemIfUnchanged(userId, initial, newer, ttl)).isTrue();
            assertThat(store.putItemIfUnchanged(userId, initial, staleOverwrite, ttl))
                    .isFalse();

            assertThat(store.findCart(userId).items()).containsExactly(newer);
        } finally {
            TenantContext.clear();
            redisTemplate.delete(key);
            connectionFactory.destroy();
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_CART_REDIS_ACCEPTANCE", matches = "true")
    void sameUserIdIsIsolatedAcrossTenants() {
        String host = System.getenv().getOrDefault("CART_REDIS_HOST", "127.0.0.1");
        int port = Integer.parseInt(System.getenv().getOrDefault("CART_REDIS_PORT", "6379"));
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(host, port);
        JedisConnectionFactory connectionFactory = new JedisConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        StringRedisTemplate redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        ObjectProvider<StringRedisTemplate> provider = mock();
        when(provider.getIfAvailable()).thenReturn(redisTemplate);
        RedisCartStore store = new RedisCartStore(provider, new ObjectMapper().findAndRegisterModules());
        Long userId = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
        String tenantOneKey = "cart:tenant:1:user:" + userId;
        String tenantTwoKey = "cart:tenant:2:user:" + userId;
        LocalDateTime now = LocalDateTime.parse("2026-01-01T00:00:00");
        CartItem tenantOneItem = new CartItem(1001L, 501L, 1, true, now, now);
        CartItem tenantTwoItem = new CartItem(2001L, 502L, 2, true, now, now);
        try {
            TenantContext.setTenantId(1L);
            store.putItem(userId, tenantOneItem, Duration.ofMinutes(10));
            TenantContext.setTenantId(2L);
            store.putItem(userId, tenantTwoItem, Duration.ofMinutes(10));

            TenantContext.setTenantId(1L);
            assertThat(store.findCart(userId).items()).containsExactly(tenantOneItem);
            store.removeMatchingItems(userId, List.of(tenantOneItem), Duration.ofMinutes(10));
            TenantContext.setTenantId(2L);
            assertThat(store.findCart(userId).items()).containsExactly(tenantTwoItem);
            assertThat(redisTemplate.hasKey(tenantOneKey)).isFalse();
            assertThat(redisTemplate.hasKey(tenantTwoKey)).isTrue();
        } finally {
            TenantContext.clear();
            redisTemplate.delete(List.of(tenantOneKey, tenantTwoKey));
            connectionFactory.destroy();
        }
    }
}
