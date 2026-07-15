package com.example.monkey.cart.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.monkey.cart.domain.CartItem;
import com.example.monkey.cart.domain.CartSnapshot;
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
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

class RedisCartStoreLocalAcceptanceTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_CART_REDIS_ACCEPTANCE", matches = "true")
    void luaCleanupConditionallyDeletesAndRefreshesTtlOnOneIsolatedKey() {
        String host = System.getenv().getOrDefault("CART_REDIS_HOST", "127.0.0.1");
        int port = Integer.parseInt(System.getenv().getOrDefault("CART_REDIS_PORT", "6379"));
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(host, port);
        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(configuration);
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
            store.save(new CartSnapshot(userId, List.of(changed, unchanged)), Duration.ofMinutes(5));

            store.removeMatchingItems(userId, List.of(checkoutSnapshot, unchanged), Duration.ofMinutes(10));

            assertThat(store.findCart(userId).items()).containsExactly(changed);
            assertThat(redisTemplate.getExpire(key, TimeUnit.SECONDS)).isBetween(500L, 600L);
        } finally {
            redisTemplate.delete(key);
            connectionFactory.destroy();
        }
    }
}
