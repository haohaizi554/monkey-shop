package com.example.monkey.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;

class ShedLockConfigTest {

    @Test
    void lockProviderUsesRedisConnectionFactory() {
        RedisConnectionFactory connectionFactory = mock(RedisConnectionFactory.class);
        ShedLockConfig config = new ShedLockConfig();

        LockProvider provider = config.lockProvider(connectionFactory, "monkeyshop-test");

        assertThat(provider).isNotNull();
    }

    @Test
    void schedulerLockDefaultsAreExternalized() {
        EnableSchedulerLock annotation = ShedLockConfig.class.getAnnotation(EnableSchedulerLock.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.defaultLockAtMostFor()).isEqualTo("${app.shedlock.default-lock-at-most-for:PT30M}");
    }
}
