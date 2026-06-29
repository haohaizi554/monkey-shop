package com.example.monkey.config;

import java.util.concurrent.Executor;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableAsync
public class ObservabilityConfig {

    @Bean(name = "observabilityTaskExecutor")
    public Executor observabilityTaskExecutor(
            @Value("${app.observability.async.core-pool-size:2}") int corePoolSize,
            @Value("${app.observability.async.max-pool-size:8}") int maxPoolSize,
            @Value("${app.observability.async.queue-capacity:1000}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("observability-");
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setTaskDecorator(mdcTaskDecorator());
        executor.initialize();
        return executor;
    }

    private static TaskDecorator mdcTaskDecorator() {
        return task -> {
            var contextMap = MDC.getCopyOfContextMap();
            return () -> {
                var previousContext = MDC.getCopyOfContextMap();
                if (contextMap == null) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(contextMap);
                }
                try {
                    task.run();
                } finally {
                    if (previousContext == null) {
                        MDC.clear();
                    } else {
                        MDC.setContextMap(previousContext);
                    }
                }
            };
        };
    }
}
