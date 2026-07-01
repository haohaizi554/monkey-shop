package com.example.monkey.shared.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.redisson.client.RedisConnectionException;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;

class RedissonConfigTest {

    @Test
    void redissonClientBeanFailsFastWhenRedisIsUnavailable() {
        assertThatThrownBy(() -> new RedissonConfig().redissonClient("localhost", 6379, "", "", false))
                .isInstanceOf(RedisConnectionException.class);
    }

    @Test
    void singleServerConfigUsesRedisSchemeAndExternalizedCredentials() {
        Config config = RedissonConfig.singleServerConfig("redis.internal", 6380, "monkeyshop", "secret", false);

        SingleServerConfig server = config.useSingleServer();
        assertThat(server.getAddress()).isEqualTo("redis://redis.internal:6380");
        assertThat(server.getUsername()).isEqualTo("monkeyshop");
        assertThat(server.getPassword()).isEqualTo("secret");
    }

    @Test
    void singleServerConfigUsesRedissSchemeWhenSslIsEnabled() {
        Config config = RedissonConfig.singleServerConfig("redis.internal", 6380, "", "", true);

        assertThat(config.useSingleServer().getAddress()).isEqualTo("rediss://redis.internal:6380");
    }
}
