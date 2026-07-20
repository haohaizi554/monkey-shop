package com.example.monkey.shared.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.monkey.shared.domain.security.ApiRateLimiter.RateLimitDecision;
import com.example.monkey.shared.domain.security.RateLimitPolicy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

class ApiRateLimitServiceLocalRedisAcceptanceTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_RATE_LIMIT_REDIS_ACCEPTANCE", matches = "true")
    void atomicCounterRepairsMissingTtlAndRejectsAtCapacity() throws Exception {
        String host = System.getenv().getOrDefault("RATE_LIMIT_REDIS_HOST", "127.0.0.1");
        int port = Integer.parseInt(System.getenv().getOrDefault("RATE_LIMIT_REDIS_PORT", "6379"));
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(host, port);
        JedisConnectionFactory connectionFactory = new JedisConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        StringRedisTemplate redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();

        String clientIp = "acceptance-" + UUID.randomUUID();
        String key = "api:rate:register:edge-ip:" + sha256(clientIp);
        Duration window = Duration.ofSeconds(30);
        RateLimitProperties properties =
                new RateLimitProperties(new RateLimitProperties.Register(2, window, 5, Duration.ofHours(1)));
        ApiRateLimitService service =
                new ApiRateLimitService(redisTemplate, true, true, Duration.ofHours(24), properties, null);

        try {
            redisTemplate.opsForValue().set(key, "0");
            assertThat(redisTemplate.getExpire(key, TimeUnit.MILLISECONDS)).isEqualTo(-1);

            RateLimitDecision first = service.consume(RateLimitPolicy.REGISTER, clientIp, "anonymous");
            RateLimitDecision second = service.consume(RateLimitPolicy.REGISTER, clientIp, "anonymous");
            RateLimitDecision rejected = service.consume(RateLimitPolicy.REGISTER, clientIp, "anonymous");

            assertThat(first.allowed()).isTrue();
            assertThat(second.allowed()).isTrue();
            assertThat(rejected.allowed()).isFalse();
            assertThat(redisTemplate.opsForValue().get(key)).isEqualTo("3");
            assertThat(redisTemplate.getExpire(key, TimeUnit.SECONDS)).isBetween(1L, window.toSeconds());
        } finally {
            redisTemplate.delete(key);
            connectionFactory.destroy();
        }
    }

    private static String sha256(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
