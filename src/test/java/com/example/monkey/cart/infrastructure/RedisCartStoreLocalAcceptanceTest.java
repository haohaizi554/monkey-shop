package com.example.monkey.cart.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.monkey.cart.domain.CartItem;
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
        String key = "cart:user:" + userId;
        LocalDateTime now = LocalDateTime.parse("2026-01-01T00:00:00");
        CartItem checkoutSnapshot = new CartItem(1001L, 501L, 2, true, now, now);
        CartItem changed = new CartItem(1001L, 501L, 3, true, now, now.plusMinutes(1));
        CartItem unchanged = new CartItem(1002L, 501L, 1, true, now, now);
        try {
            store.putItem(userId, changed, Duration.ofMinutes(5));
            store.putItem(userId, unchanged, Duration.ofMinutes(5));

            store.removeMatchingItems(userId, List.of(checkoutSnapshot, unchanged), Duration.ofMinutes(10));

            assertThat(store.findCart(userId).items()).containsExactly(changed);
            assertThat(redisTemplate.getExpire(key, TimeUnit.SECONDS)).isBetween(500L, 600L);
        } finally {
            redisTemplate.delete(key);
            connectionFactory.destroy();
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_CART_REDIS_ACCEPTANCE", matches = "true")
    void delayedFieldMutationAfterCleanupCannotRestoreOtherSnapshotFields() {
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
        String key = "cart:user:" + userId;
        Duration ttl = Duration.ofMinutes(10);
        LocalDateTime now = LocalDateTime.parse("2026-01-01T00:00:00");
        LocalDateTime later = now.plusMinutes(1);
        List<CartItem> checkoutSnapshots = List.of(
                new CartItem(1001L, 501L, 2, true, now, now),
                new CartItem(1002L, 501L, 1, true, now, now),
                new CartItem(1003L, 501L, 1, true, now, now),
                new CartItem(1004L, 501L, 1, true, now, now));
        try {
            checkoutSnapshots.forEach(item -> store.putItem(userId, item, ttl));
            var staleMutationRead = store.findCart(userId);

            store.removeMatchingItems(userId, checkoutSnapshots, ttl);
            CartItem quantityChanged = staleMutationRead.items().stream()
                    .filter(item -> item.skuId().equals(1001L))
                    .findFirst()
                    .orElseThrow()
                    .withQuantity(3, later);
            CartItem unselected = staleMutationRead.items().stream()
                    .filter(item -> item.skuId().equals(1002L))
                    .findFirst()
                    .orElseThrow()
                    .select(false, later);
            CartItem readded = new CartItem(1003L, 501L, 1, true, later, later);
            CartItem brandNew = new CartItem(2001L, 502L, 1, true, later, later);
            List<CartItem> postCheckoutItems = List.of(quantityChanged, unselected, readded, brandNew);
            postCheckoutItems.forEach(item -> store.putItem(userId, item, ttl));

            assertThat(store.findCart(userId).items()).containsExactlyInAnyOrderElementsOf(postCheckoutItems);
            assertThat(store.findCart(userId).items())
                    .noneMatch(item -> item.skuId().equals(1004L));
        } finally {
            redisTemplate.delete(key);
            connectionFactory.destroy();
        }
    }
}
