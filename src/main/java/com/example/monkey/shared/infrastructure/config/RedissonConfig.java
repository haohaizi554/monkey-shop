package com.example.monkey.shared.infrastructure.config;

import java.util.Arrays;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.ClusterServersConfig;
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
            @Value("${spring.data.redis.ssl.enabled:false}") boolean sslEnabled,
            @Value("${spring.data.redis.cluster.nodes:}") String clusterNodes) {
        return Redisson.create(redisConfig(host, port, username, password, sslEnabled, clusterNodes));
    }

    public RedissonClient redissonClient(String host, int port, String username, String password, boolean sslEnabled) {
        return Redisson.create(singleServerConfig(host, port, username, password, sslEnabled));
    }

    static Config singleServerConfig(String host, int port, String username, String password, boolean sslEnabled) {
        return redisConfig(host, port, username, password, sslEnabled, "");
    }

    static Config redisConfig(
            String host, int port, String username, String password, boolean sslEnabled, String clusterNodes) {
        Config config = new Config();
        if (StringUtils.hasText(username)) {
            config.setUsername(username);
        }
        if (StringUtils.hasText(password)) {
            config.setPassword(password);
        }
        if (StringUtils.hasText(clusterNodes)) {
            String[] addresses = Arrays.stream(clusterNodes.split(","))
                    .map(String::trim)
                    .filter(StringUtils::hasText)
                    .map(node -> redisAddress(node, sslEnabled))
                    .toArray(String[]::new);
            if (addresses.length == 0) {
                throw new IllegalArgumentException("Redis cluster nodes must not be empty");
            }
            ClusterServersConfig server = config.useClusterServers();
            server.addNodeAddress(addresses);
            return config;
        }
        SingleServerConfig server = config.useSingleServer();
        server.setAddress(redisAddress(host, port, sslEnabled));
        return config;
    }

    private static String redisAddress(String host, int port, boolean sslEnabled) {
        return redisAddress(host + ":" + port, sslEnabled);
    }

    private static String redisAddress(String endpoint, boolean sslEnabled) {
        String normalized = endpoint.trim();
        if (normalized.startsWith("redis://")) {
            normalized = normalized.substring("redis://".length());
        } else if (normalized.startsWith("rediss://")) {
            normalized = normalized.substring("rediss://".length());
        }
        String scheme = sslEnabled ? "rediss://" : "redis://";
        return scheme + normalized;
    }
}
