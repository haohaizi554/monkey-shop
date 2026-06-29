package com.example.monkey.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.redis.spring.RedisLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;

@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "${app.shedlock.default-lock-at-most-for:PT30M}")
public class ShedLockConfig {

    @Bean
    public LockProvider lockProvider(
            RedisConnectionFactory redisConnectionFactory,
            @Value("${app.shedlock.environment:${spring.application.name:monkeyshop}}") String environment) {
        return new RedisLockProvider(redisConnectionFactory, environment);
    }
}
