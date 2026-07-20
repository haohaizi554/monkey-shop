package com.example.monkey.shared.infrastructure.id;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
class SnowflakeLeaseSchedulingConfiguration {

    static final String SCHEDULER_BEAN_NAME = "snowflakeLeaseTaskScheduler";

    @Bean(name = SCHEDULER_BEAN_NAME)
    ThreadPoolTaskScheduler snowflakeLeaseTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("snowflake-lease-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(5);
        return scheduler;
    }
}
