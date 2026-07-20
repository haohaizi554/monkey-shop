package com.example.monkey.shared.infrastructure.id;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

class SnowflakeNodeIdentityLocalRedisAcceptanceTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_SNOWFLAKE_REDIS_ACCEPTANCE", matches = "true")
    void distinctReplicasAcquireDistinctNodeIdentities() {
        String host = System.getenv().getOrDefault("SNOWFLAKE_REDIS_HOST", "127.0.0.1");
        int port = Integer.parseInt(System.getenv().getOrDefault("SNOWFLAKE_REDIS_PORT", "6379"));
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(host, port);
        JedisConnectionFactory connectionFactory = new JedisConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        StringRedisTemplate redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();

        String namespace = "acceptance-" + UUID.randomUUID();
        SnowflakeNodeIdentity first = SnowflakeNodeIdentity.distributed(
                redisTemplate, "replica-a", namespace, Duration.ofSeconds(30), System::nanoTime);
        SnowflakeNodeIdentity second = SnowflakeNodeIdentity.distributed(
                redisTemplate, "replica-b", namespace, Duration.ofSeconds(30), System::nanoTime);

        try {
            assertThat(first.nodeId()).isNotEqualTo(second.nodeId());
            assertThat(first.workerId()).isBetween(0L, 31L);
            assertThat(first.datacenterId()).isBetween(0L, 31L);
            assertThat(second.workerId()).isBetween(0L, 31L);
            assertThat(second.datacenterId()).isBetween(0L, 31L);

            AtomicLong now = new AtomicLong(SnowflakeIdGenerator.CUSTOM_EPOCH_MILLIS + 1_000L);
            SnowflakeIdGenerator firstGenerator = new SnowflakeIdGenerator(first, now::get);
            SnowflakeIdGenerator secondGenerator = new SnowflakeIdGenerator(second, now::get);
            assertThat(firstGenerator.nextId()).isNotEqualTo(secondGenerator.nextId());

            redisTemplate.delete("snowflake:nodes:{" + sha256(namespace) + "}");
            first.renewLease();
            assertThatThrownBy(first::assertLeaseValid)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("refusing to generate");
        } finally {
            second.destroy();
            first.destroy();
            connectionFactory.destroy();
        }
    }

    private static String sha256(String value) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of()
                    .formatHex(digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
