package com.example.monkey.shared.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;
import org.redisson.client.RedisConnectionException;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;

class RedissonConfigTest {

    @Test
    void redissonClientBeanFailsFastWhenRedisIsUnavailable() throws IOException {
        int unusedPort = unusedLocalPort();

        assertThatThrownBy(() -> new RedissonConfig().redissonClient("127.0.0.1", unusedPort, "", "", false))
                .isInstanceOf(RedisConnectionException.class);
    }

    @Test
    void singleServerConfigUsesRedisSchemeAndExternalizedCredentials() {
        Config config = RedissonConfig.singleServerConfig("redis.internal", 6380, "monkeyshop", "secret", false);

        SingleServerConfig server = config.useSingleServer();
        assertThat(server.getAddress()).isEqualTo("redis://redis.internal:6380");
        assertThat(configValue(config, "getUsername")).isEqualTo("monkeyshop");
        assertThat(configValue(config, "getPassword")).isEqualTo("secret");
    }

    @Test
    void singleServerConfigUsesRedissSchemeWhenSslIsEnabled() {
        Config config = RedissonConfig.singleServerConfig("redis.internal", 6380, "", "", true);

        assertThat(config.useSingleServer().getAddress()).isEqualTo("rediss://redis.internal:6380");
    }

    private static int unusedLocalPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(false);
            return socket.getLocalPort();
        }
    }

    private static Object configValue(Object config, String getterName) {
        try {
            return config.getClass().getMethod(getterName).invoke(config);
        } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException exception) {
            throw new AssertionError("Redisson config getter is unavailable: " + getterName, exception);
        }
    }
}
