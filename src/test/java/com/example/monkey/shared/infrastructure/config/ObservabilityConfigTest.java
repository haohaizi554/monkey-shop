package com.example.monkey.shared.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.monkey.shared.application.observability.AuditService;
import com.example.monkey.shared.application.tenant.TenantContext;
import com.example.monkey.shared.domain.observability.AuditLogStore;
import com.example.monkey.shared.domain.observability.AuditLogStore.AuditEventRecord;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class ObservabilityConfigTest {

    @Test
    void logbackUsesJsonEncodingWithTraceAndUserMdcAndRetention() throws IOException {
        String logback = Files.readString(Path.of("src/main/resources/logback-spring.xml"), StandardCharsets.UTF_8);

        assertThat(logback).contains("net.logstash.logback.encoder.LogstashEncoder");
        assertThat(logback).contains("<includeMdcKeyName>traceId</includeMdcKeyName>");
        assertThat(logback).contains("<includeMdcKeyName>userId</includeMdcKeyName>");
        assertThat(logback).contains("<maxFileSize>100MB</maxFileSize>");
        assertThat(logback).contains("<maxHistory>30</maxHistory>");
        assertThat(logback).contains("<totalSizeCap>10GB</totalSizeCap>");
        assertThat(logback).contains("MaskingJsonGeneratorDecorator");
    }

    @Test
    void applicationExposesPrometheusAndKeepsObservabilityPiiOffByDefault() throws IOException {
        String application = Files.readString(Path.of("src/main/resources/application.yml"), StandardCharsets.UTF_8);
        String prod = Files.readString(Path.of("src/main/resources/application-prod.yml"), StandardCharsets.UTF_8);

        assertThat(application).contains("include: health,prometheus,loggers");
        assertThat(application).contains("show-values: NEVER");
        assertThat(application).contains("send-default-pii: false");
        assertThat(application).contains("exporter: ${OTEL_TRACES_EXPORTER:none}");
        assertThat(application).contains("retention-days: ${APP_AUDIT_RETENTION_DAYS:180}");
        assertThat(prod).contains("exporter: ${OTEL_TRACES_EXPORTER:otlp}");
        assertThat(prod).contains("send-default-pii: false");
    }

    @Test
    void asyncExecutorUsesExternalizedSettingsAndPropagatesMdc() throws Exception {
        ObservabilityConfig config = new ObservabilityConfig();
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) config.observabilityTaskExecutor(1, 1, 3);
        try {
            assertThat(executor.getThreadNamePrefix()).isEqualTo("observability-");
            assertThat(executor.getCorePoolSize()).isEqualTo(1);
            assertThat(executor.getMaxPoolSize()).isEqualTo(1);
            assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity())
                    .isEqualTo(3);

            MDC.put("traceId", "trace-123");
            MDC.put("userId", "7");
            CountDownLatch firstTask = new CountDownLatch(1);
            AtomicReference<String> traceId = new AtomicReference<>();
            AtomicReference<String> userId = new AtomicReference<>();
            AtomicReference<String> threadName = new AtomicReference<>();
            executor.execute(() -> {
                traceId.set(MDC.get("traceId"));
                userId.set(MDC.get("userId"));
                threadName.set(Thread.currentThread().getName());
                firstTask.countDown();
            });
            assertThat(firstTask.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(traceId).hasValue("trace-123");
            assertThat(userId).hasValue("7");
            assertThat(threadName.get()).startsWith("observability-");

            MDC.clear();
            CountDownLatch secondTask = new CountDownLatch(1);
            AtomicReference<String> clearedTraceId = new AtomicReference<>("stale");
            executor.execute(() -> {
                clearedTraceId.set(MDC.get("traceId"));
                secondTask.countDown();
            });
            assertThat(secondTask.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(clearedTraceId).hasValue(null);
        } finally {
            MDC.clear();
            executor.shutdown();
        }
    }

    @Test
    void asyncAuditUsesSubmittingTenantWithoutLeakingItToTheNextTask() throws Exception {
        ObservabilityConfig config = new ObservabilityConfig();
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) config.observabilityTaskExecutor(1, 1, 3);
        CountDownLatch savesCompleted = new CountDownLatch(2);
        List<Long> savedTenantIds = new CopyOnWriteArrayList<>();
        AuditService auditService =
                new AuditService(new TenantCapturingAuditLogStore(savedTenantIds, savesCompleted), 180);

        try {
            TenantContext.setTenantId(2L);
            executor.execute(() -> recordPaymentAdminRead(auditService, "payment-tenant-2"));

            TenantContext.clear();
            executor.execute(() -> recordPaymentAdminRead(auditService, "payment-platform"));

            assertThat(savesCompleted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(savedTenantIds).containsExactly(2L, TenantContext.PLATFORM_TENANT_ID);
        } finally {
            TenantContext.clear();
            executor.shutdown();
        }
    }

    private static void recordPaymentAdminRead(AuditService auditService, String subject) {
        auditService.record(
                AuditService.PAYMENT_ADMIN_READ,
                AuditService.OUTCOME_SUCCESS,
                7L,
                "ADMIN",
                subject,
                "127.0.0.1",
                "orderId=10");
    }

    private static final class TenantCapturingAuditLogStore implements AuditLogStore {

        private final List<Long> savedTenantIds;
        private final CountDownLatch savesCompleted;

        private TenantCapturingAuditLogStore(List<Long> savedTenantIds, CountDownLatch savesCompleted) {
            this.savedTenantIds = savedTenantIds;
            this.savesCompleted = savesCompleted;
        }

        @Override
        public void save(AuditEventRecord record) {
            savedTenantIds.add(TenantContext.currentTenantIdOrDefault());
            savesCompleted.countDown();
        }

        @Override
        public List<AuditEventRecord> findFirst50ByTraceId(String traceId) {
            return List.of();
        }

        @Override
        public long deleteCreatedBefore(LocalDateTime cutoff) {
            return 0;
        }
    }
}
