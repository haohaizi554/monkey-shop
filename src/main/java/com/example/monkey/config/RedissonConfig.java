package com.example.monkey.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
public class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(
            @Value("${spring.data.redis.host:localhost}") String host,
            @Value("${spring.data.redis.port:6379}") int port,
            @Value("${spring.data.redis.username:}") String username,
            @Value("${spring.data.redis.password:}") String password,
            @Value("${spring.data.redis.ssl.enabled:false}") boolean sslEnabled) {
        return Redisson.create(singleServerConfig(host, port, username, password, sslEnabled));
    }

    static Config singleServerConfig(String host, int port, String username, String password, boolean sslEnabled) {
        Config config = new Config();
        SingleServerConfig server = config.useSingleServer().setAddress(redisAddress(host, port, sslEnabled));
        if (StringUtils.hasText(username)) {
            server.setUsername(username);
        }
        if (StringUtils.hasText(password)) {
            server.setPassword(password);
        }
        return config;
    }

    private static String redisAddress(String host, int port, boolean sslEnabled) {
        String scheme = sslEnabled ? "rediss://" : "redis://";
        return scheme + host + ":" + port;
    }
}
