package com.example.monkey.shared.infrastructure.id;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class SnowflakeLeaseSchedulingConfigurationTest {

    @Test
    void renewalRunsOnAnIsolatedScheduler() throws NoSuchMethodException {
        Scheduled scheduled =
                SnowflakeNodeIdentity.class.getDeclaredMethod("renewLease").getAnnotation(Scheduled.class);

        assertThat(scheduled.scheduler()).isEqualTo(SnowflakeLeaseSchedulingConfiguration.SCHEDULER_BEAN_NAME);
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext(SnowflakeLeaseSchedulingConfiguration.class)) {
            assertThat(context.getBean(SnowflakeLeaseSchedulingConfiguration.SCHEDULER_BEAN_NAME))
                    .isInstanceOf(ThreadPoolTaskScheduler.class);
        }
    }
}
