package com.example.monkey.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.monkey.order.domain.OrderStore;
import com.example.monkey.order.domain.OrderStore.OrderRecord;
import com.example.monkey.payment.application.dto.PaymentCreateRequestDto;
import com.example.monkey.payment.application.dto.PaymentRefundRequestDto;
import com.example.monkey.payment.application.dto.PaymentRefundResponseDto;
import com.example.monkey.payment.application.dto.PaymentResponseDto;
import com.example.monkey.payment.domain.PaymentCallbackReplayGuard;
import com.example.monkey.payment.domain.PaymentFailureClassification;
import com.example.monkey.payment.domain.PaymentGateway;
import com.example.monkey.payment.domain.PaymentGatewayException;
import com.example.monkey.payment.domain.PaymentGatewayResult;
import com.example.monkey.payment.domain.PaymentLedgerStatus;
import com.example.monkey.payment.domain.PaymentLedgerType;
import com.example.monkey.payment.domain.PaymentMethod;
import com.example.monkey.payment.domain.PaymentOperationAttempt;
import com.example.monkey.payment.domain.PaymentOperationState;
import com.example.monkey.payment.domain.PaymentOrder;
import com.example.monkey.payment.domain.PaymentQueryAttempt;
import com.example.monkey.payment.domain.PaymentStatus;
import com.example.monkey.payment.domain.PaymentStore;
import com.example.monkey.payment.domain.PaymentTransitionPolicy;
import com.example.monkey.payment.domain.PaymentTransitionResolver;
import com.example.monkey.payment.domain.RefundAuditState;
import com.example.monkey.payment.infrastructure.JdbcPaymentRecoveryTenantSource;
import com.example.monkey.payment.infrastructure.JpaPaymentStore;
import com.example.monkey.payment.infrastructure.PaymentLedgerEntity;
import com.example.monkey.payment.infrastructure.PaymentLedgerRepository;
import com.example.monkey.payment.infrastructure.PaymentOrderEntity;
import com.example.monkey.payment.infrastructure.PaymentOrderRepository;
import com.example.monkey.payment.infrastructure.PaymentReconciliationReportRepository;
import com.example.monkey.payment.infrastructure.RequiresNewPaymentTransactions;
import com.example.monkey.shared.application.observability.AuditService;
import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.application.tenant.TenantContext;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.shared.domain.id.IdGenerator;
import com.example.monkey.shared.infrastructure.observability.AuditLogRepository;
import com.example.monkey.shared.infrastructure.observability.JpaAuditLogStore;
import com.example.monkey.shared.infrastructure.privacy.PiiCryptoService;
import com.example.monkey.user.domain.UserAccountStore;
import com.example.monkey.user.domain.UserMfaVerifier;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@TestPropertySource(
        properties = {
            "spring.flyway.enabled=true",
            "spring.jpa.hibernate.ddl-auto=validate",
            "app.payment.callback-secret=local-mysql-acceptance-secret"
        })
@MockitoBean(types = PiiCryptoService.class)
@EnabledIfSystemProperty(named = "task4.local-mysql", matches = "true")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentLocalMySqlAcceptanceTest {

    private static final SessionUser USER = new SessionUser(42L, "USER");

    private final PaymentOrderRepository paymentOrderRepository;
    private final PaymentLedgerRepository paymentLedgerRepository;
    private final PaymentReconciliationReportRepository reconciliationReportRepository;
    private final PiiCryptoService piiCryptoService;
    private final AuditLogRepository auditLogRepository;
    private final PlatformTransactionManager transactionManager;
    private final JdbcTemplate jdbcTemplate;
    private final Map<Long, OrderRecord> visibleOrders = new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong(970_100L);
    private RecordingGateway gateway;
    private PaymentApplicationService service;

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> requiredProperty("task4.mysql.url"));
        registry.add("spring.datasource.username", () -> System.getProperty("task4.mysql.username", "root"));
        registry.add("spring.datasource.password", () -> System.getProperty("task4.mysql.password", ""));
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @Autowired
    PaymentLocalMySqlAcceptanceTest(
            PaymentOrderRepository paymentOrderRepository,
            PaymentLedgerRepository paymentLedgerRepository,
            PaymentReconciliationReportRepository reconciliationReportRepository,
            PiiCryptoService piiCryptoService,
            AuditLogRepository auditLogRepository,
            PlatformTransactionManager transactionManager,
            DataSource dataSource) {
        this.paymentOrderRepository = paymentOrderRepository;
        this.paymentLedgerRepository = paymentLedgerRepository;
        this.reconciliationReportRepository = reconciliationReportRepository;
        this.piiCryptoService = piiCryptoService;
        this.auditLogRepository = auditLogRepository;
        this.transactionManager = transactionManager;
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(1L);
        jdbcTemplate.update("DELETE FROM audit_log");
        jdbcTemplate.update("DELETE FROM payment_ledger");
        jdbcTemplate.update("DELETE FROM payment_order");
        jdbcTemplate.update("DELETE FROM orders WHERE id >= 970000");
        jdbcTemplate.update("""
                INSERT INTO tenant (id, code, name, status, plan, expires_at)
                VALUES (2, 'task4-local-second', 'Task 4 Local Second Tenant', 'ACTIVE', 'STANDARD',
                        '2099-12-31 23:59:59')
                ON DUPLICATE KEY UPDATE name = VALUES(name)
                """);
        visibleOrders.clear();
        ids.set(970_100L);
        gateway = new RecordingGateway();
        service = applicationService(gateway, mock(AuditService.class));
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void emptySchemaMigratesThroughV51AndHibernateValidates() {
        assertThat(jdbcTemplate.queryForObject("""
                        SELECT version
                        FROM flyway_schema_history
                        WHERE success = 1
                        ORDER BY installed_rank DESC
                        LIMIT 1
                        """, String.class)).isEqualTo("51");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payment_order", Long.class))
                .isZero();
    }

    @Test
    void v50LegacyDuplicateBlocksThenRepairMigratesThroughV51() throws Exception {
        String url = requiredProperty("task4.mysql.legacy-url");
        String username = System.getProperty("task4.mysql.username", "root");
        String password = System.getProperty("task4.mysql.password", "");
        Flyway flyway = Flyway.configure()
                .dataSource(url, username, password)
                .target(MigrationVersion.fromVersion("50"))
                .load();
        flyway.migrate();
        try (Connection connection = DriverManager.getConnection(url, username, password)) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/fixtures/payment_v50_task4_review.sql"));
        }

        Flyway latest = Flyway.configure().dataSource(url, username, password).load();
        Throwable blocked = catchThrowable(latest::migrate);

        assertThat(blocked).isNotNull();
        assertThat(causeMessages(blocked)).contains("ck_v51_resolve_duplicate_active_payment_intents");

        JdbcTemplate fixtureJdbc = new JdbcTemplate(new DriverManagerDataSource(url, username, password));
        assertThat(fixtureJdbc.update("DELETE FROM payment_order WHERE id = 905102"))
                .isEqualTo(1);
        latest.repair();
        latest.migrate();

        assertThat(fixtureJdbc.queryForObject("""
                        SELECT version
                        FROM flyway_schema_history
                        WHERE success = 1
                        ORDER BY installed_rank DESC
                        LIMIT 1
                        """, String.class)).isEqualTo("51");
        assertThat(fixtureJdbc.queryForMap("""
                        SELECT operation_state, attempt_count, lease_expires_at,
                               last_failure_classification, terminal_failure_code
                        FROM payment_order WHERE id = 905103
                        """))
                .containsEntry("operation_state", "LEGACY_UNREPLAYABLE")
                .containsEntry("attempt_count", 0)
                .containsEntry("lease_expires_at", null)
                .containsEntry("last_failure_classification", "LEGACY_UNKNOWN")
                .containsEntry("terminal_failure_code", null);
        assertThat(fixtureJdbc.queryForMap("""
                        SELECT operation_state, attempt_count, lease_expires_at,
                               last_failure_classification, terminal_failure_code, audit_state
                        FROM payment_ledger WHERE id = 905104
                        """))
                .containsEntry("operation_state", "LEGACY_UNREPLAYABLE")
                .containsEntry("attempt_count", 0)
                .containsEntry("lease_expires_at", null)
                .containsEntry("last_failure_classification", "LEGACY_UNKNOWN")
                .containsEntry("terminal_failure_code", null)
                .containsEntry("audit_state", "NONE");

        Map<String, Object> legacyPending = fixtureJdbc.queryForMap("""
                SELECT operation_state, attempt_count, lease_expires_at,
                       last_failure_classification, query_attempt_count,
                       query_lease_expires_at, next_query_at, request_fingerprint
                FROM payment_order WHERE id = 905105
                """);
        assertThat(legacyPending)
                .containsEntry("operation_state", "LEGACY_UNREPLAYABLE")
                .containsEntry("attempt_count", 0)
                .containsEntry("lease_expires_at", null)
                .containsEntry("last_failure_classification", "LEGACY_UNKNOWN")
                .containsEntry("query_attempt_count", 0)
                .containsEntry("query_lease_expires_at", null);
        assertThat(legacyPending.get("next_query_at")).isNotNull();

        allowOrder(970_019L, 1L);
        paymentOrderRepository.saveAndFlush(legacyQueryReadyPayment(
                970_460L,
                970_019L,
                "PAY970460",
                "local-legacy-pending",
                legacyPending.get("request_fingerprint").toString(),
                toLocalDateTime(legacyPending.get("next_query_at"))));
        gateway.queryResult =
                new PaymentGatewayResult(PaymentStatus.PAID, "LOCAL-LEGACY-QUERY", null, new BigDecimal("100.00"));

        assertThat(service.queryTimedOutPayments()).isEqualTo(1);
        Throwable replay = catchThrowable(() -> service.createPayment(
                USER, new PaymentCreateRequestDto(970_019L, PaymentMethod.WECHAT, null, null), "local-legacy-pending"));

        assertThat(gateway.queryPaymentNos).containsExactly("PAY970460");
        assertThat(gateway.createTokens).isEmpty();
        assertThat(replay)
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        assertThat(jdbcTemplate.queryForMap("""
                        SELECT status, operation_state, query_attempt_count, next_query_at
                        FROM payment_order WHERE id = 970460
                        """))
                .containsEntry("status", "PAID")
                .containsEntry("operation_state", "LEGACY_UNREPLAYABLE")
                .containsEntry("query_attempt_count", 1)
                .containsEntry("next_query_at", null);
    }

    @Test
    void constraintsRejectSameTenantDuplicatesAndAllowCrossTenantRows() {
        allowOrder(970_001L, 1L);
        allowOrder(970_002L, 1L);
        paymentOrderRepository.saveAndFlush(reservedPayment(
                970_200L,
                970_001L,
                "PAY970200",
                "same-key",
                1L,
                LocalDateTime.now().plusMinutes(5)));

        Throwable activeOrderConflict = catchThrowable(() -> paymentOrderRepository.saveAndFlush(reservedPayment(
                970_201L,
                970_001L,
                "PAY970201",
                "different-key",
                1L,
                LocalDateTime.now().plusMinutes(5))));
        Throwable userKeyConflict = catchThrowable(() -> paymentOrderRepository.saveAndFlush(reservedPayment(
                970_202L,
                970_002L,
                "PAY970202",
                "same-key",
                1L,
                LocalDateTime.now().plusMinutes(5))));
        paymentOrderRepository.saveAndFlush(reservedPayment(
                970_203L,
                970_001L,
                "PAY970203",
                "same-key",
                2L,
                LocalDateTime.now().plusMinutes(5)));

        assertThat(activeOrderConflict).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(causeMessages(activeOrderConflict)).contains("uk_payment_order_active_order");
        assertThat(userKeyConflict).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(causeMessages(userKeyConflict)).contains("uk_payment_order_user_key");
        assertThat(jdbcTemplate.queryForObject("""
                        SELECT COUNT(*)
                        FROM payment_order
                        WHERE order_id = 970001 AND idempotency_key = 'same-key'
                        """, Long.class)).isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject("""
                        SELECT COUNT(DISTINCT tenant_id)
                        FROM payment_order
                        WHERE order_id = 970001 AND idempotency_key = 'same-key'
                        """, Long.class)).isEqualTo(2L);
    }

    @Test
    void concurrentWorkersClaimExpiredPaymentOnceWhileLeaseIsValid() throws Exception {
        allowOrder(970_010L, 1L);
        paymentOrderRepository.saveAndFlush(reservedPayment(
                970_300L,
                970_010L,
                "PAY970300",
                "claim-once",
                1L,
                LocalDateTime.now().minusMinutes(1)));
        BlockingGateway blockingGateway = new BlockingGateway();
        PaymentApplicationService recoveryService = applicationService(blockingGateway, mock(AuditService.class));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        ExecutorCompletionService<Integer> recoveries = new ExecutorCompletionService<>(executor);
        CountDownLatch start = new CountDownLatch(1);

        try {
            recoveries.submit(() -> recoverAfter(start, recoveryService));
            recoveries.submit(() -> recoverAfter(start, recoveryService));
            start.countDown();
            blockingGateway.awaitCreate();

            Future<Integer> nonWinner = recoveries.poll(10, TimeUnit.SECONDS);
            assertThat(nonWinner).isNotNull();
            assertThat(nonWinner.get(10, TimeUnit.SECONDS)).isZero();

            blockingGateway.releaseCreate();
            assertThat(recoveries.poll(10, TimeUnit.SECONDS).get(10, TimeUnit.SECONDS))
                    .isEqualTo(1);
        } finally {
            blockingGateway.releaseCreate();
            executor.shutdownNow();
        }

        assertThat(jdbcTemplate.queryForMap(
                        "SELECT operation_state, attempt_count FROM payment_order WHERE id = 970300"))
                .containsEntry("operation_state", "COMPLETED")
                .containsEntry("attempt_count", 2);
        assertThat(blockingGateway.createCalls()).isEqualTo(1);
    }

    @Test
    void staleCompletionAndTerminalFailureCannotOverwriteNewerAttempt() throws Exception {
        assertStalePaymentWorkerCannotOverwriteNewerAttempt(null, 970_310L, 970_011L, "PAY970310");
        assertStalePaymentWorkerCannotOverwriteNewerAttempt(
                PaymentGatewayException.rejected("CARD_DECLINED", "raw decline"), 970_311L, 970_012L, "PAY970311");
    }

    @Test
    void createReservationTakeoverPreventsStaleWorkerProviderCall() throws Exception {
        allowOrder(970_013L, 1L);
        MutableClock clock = new MutableClock(Instant.now(), ZoneId.systemDefault());
        PausingPaymentTransactions transactions = pausingTransactions(2);
        RecordingGateway staleGateway = new RecordingGateway();
        PaymentApplicationService staleService =
                applicationService(staleGateway, mock(AuditService.class), transactions, clock);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<PaymentResponseDto> staleWorker = executor.submit(() -> staleService.createPayment(
                    USER,
                    new PaymentCreateRequestDto(970_013L, PaymentMethod.WECHAT, null, null),
                    "local-create-takeover"));
            transactions.awaitPausedCommit();
            String paymentNo = jdbcTemplate.queryForObject(
                    "SELECT payment_no FROM payment_order WHERE idempotency_key = 'local-create-takeover'",
                    String.class);

            clock.advance(Duration.ofMinutes(3));
            PaymentOperationAttempt newerAttempt = claimPaymentTakeover(paymentNo, clock);
            transactions.releasePausedCommit();
            Throwable staleFailure = awaitWorkerFailure(staleWorker);

            assertThat(staleFailure)
                    .isInstanceOfSatisfying(
                            BusinessException.class,
                            failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.CONFLICT));
            assertThat(newerAttempt.attemptCount()).isEqualTo(2);
            assertThat(staleGateway.createTokens).isEmpty();
            assertThat(jdbcTemplate.queryForMap("""
                            SELECT operation_state, attempt_count, merchant_token
                            FROM payment_order WHERE idempotency_key = 'local-create-takeover'
                            """))
                    .containsEntry("operation_state", "RESERVED")
                    .containsEntry("attempt_count", 2)
                    .containsEntry("merchant_token", paymentNo);
        } finally {
            transactions.releasePausedCommit();
            executor.shutdownNow();
        }
    }

    @Test
    void refundReservationTakeoverPreventsStaleWorkerProviderCall() throws Exception {
        allowOrder(970_014L, 1L);
        paymentOrderRepository.saveAndFlush(paidPayment(970_430L, 970_014L, "PAY970430", 1L, BigDecimal.ZERO));
        MutableClock clock = new MutableClock(Instant.now(), ZoneId.systemDefault());
        PausingPaymentTransactions transactions = pausingTransactions(1);
        RecordingGateway staleGateway = new RecordingGateway();
        PaymentApplicationService staleService =
                applicationService(staleGateway, mock(AuditService.class), transactions, clock);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<PaymentRefundResponseDto> staleWorker = executor.submit(() -> staleService.refund(
                    USER,
                    new PaymentRefundRequestDto("PAY970430", new BigDecimal("30.00"), "takeover"),
                    "local-refund-takeover"));
            transactions.awaitPausedCommit();

            clock.advance(Duration.ofMinutes(3));
            PaymentOperationAttempt newerAttempt = claimRefundTakeover("PAY970430", "local-refund-takeover", clock);
            transactions.releasePausedCommit();
            Throwable staleFailure = awaitWorkerFailure(staleWorker);

            assertThat(staleFailure)
                    .isInstanceOfSatisfying(
                            BusinessException.class,
                            failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.CONFLICT));
            assertThat(newerAttempt.attemptCount()).isEqualTo(2);
            assertThat(staleGateway.refundTokens).isEmpty();
            assertThat(jdbcTemplate.queryForMap("""
                            SELECT operation_state, attempt_count
                            FROM payment_ledger
                            WHERE payment_id = 970430 AND request_key = 'local-refund-takeover'
                            """))
                    .containsEntry("operation_state", "RESERVED")
                    .containsEntry("attempt_count", 2);
            assertThat(jdbcTemplate.queryForObject(
                            "SELECT refunded_amount FROM payment_order WHERE id = 970430", BigDecimal.class))
                    .isEqualByComparingTo(BigDecimal.ZERO);
        } finally {
            transactions.releasePausedCommit();
            executor.shutdownNow();
        }
    }

    @Test
    void queryClaimTakeoverAndValidLeaseFenceProviderCalls() throws Exception {
        allowOrder(970_015L, 1L);
        paymentOrderRepository.saveAndFlush(queryReadyPayment(970_440L, 970_015L, "PAY970440", 1L));
        MutableClock clock = new MutableClock(Instant.now(), ZoneId.systemDefault());
        PausingPaymentTransactions transactions = pausingTransactions(1);
        RecordingGateway staleGateway = new RecordingGateway();
        PaymentApplicationService staleService =
                applicationService(staleGateway, mock(AuditService.class), transactions, clock);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<Integer> staleWorker = executor.submit(staleService::queryTimedOutPayments);
            transactions.awaitPausedCommit();
            clock.advance(Duration.ofMinutes(3));
            PaymentQueryAttempt newerAttempt = claimQueryTakeover("PAY970440", clock);
            transactions.releasePausedCommit();

            assertThat(staleWorker.get(10, TimeUnit.SECONDS)).isZero();
            assertThat(newerAttempt.attemptToken()).isEqualTo(2);
            assertThat(staleGateway.queryPaymentNos).isEmpty();
        } finally {
            transactions.releasePausedCommit();
            executor.shutdownNow();
        }

        allowOrder(970_016L, 1L);
        paymentOrderRepository.saveAndFlush(queryReadyPayment(970_441L, 970_016L, "PAY970441", 1L));
        BlockingQueryGateway blockingGateway = new BlockingQueryGateway();
        PaymentApplicationService queryService = applicationService(blockingGateway, mock(AuditService.class));
        ExecutorService queryWorkers = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> firstWorker = queryWorkers.submit(queryService::queryTimedOutPayments);
            blockingGateway.awaitQuery();
            Future<Integer> secondWorker = queryWorkers.submit(queryService::queryTimedOutPayments);
            assertThat(secondWorker.get(10, TimeUnit.SECONDS)).isZero();
            assertThat(blockingGateway.queryCalls()).isEqualTo(1);
            blockingGateway.releaseQuery();
            assertThat(firstWorker.get(10, TimeUnit.SECONDS)).isZero();
        } finally {
            blockingGateway.releaseQuery();
            queryWorkers.shutdownNow();
        }
        assertThat(blockingGateway.queryCalls()).isEqualTo(1);
    }

    @Test
    void scheduledQueryRecoversTenantTwoAndStaleResultCannotOverwriteNewAttempt() throws Exception {
        allowOrder(970_017L, 2L);
        paymentOrderRepository.saveAndFlush(queryReadyPayment(970_450L, 970_017L, "PAY970450", 2L));
        gateway.queryResult =
                new PaymentGatewayResult(PaymentStatus.PAID, "LOCAL-QUERY-PAY970450", null, new BigDecimal("100.00"));
        TenantContext.setTenantId(77L);

        service.queryTimedOutPaymentsScheduled();

        assertThat(TenantContext.currentTenantId()).contains(77L);
        assertThat(gateway.queryPaymentNos).containsExactly("PAY970450");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM payment_order WHERE id = 970450", String.class))
                .isEqualTo("PAID");

        TenantContext.setTenantId(1L);
        allowOrder(970_018L, 1L);
        paymentOrderRepository.saveAndFlush(queryReadyPayment(970_451L, 970_018L, "PAY970451", 1L));
        TwoWorkerQueryGateway twoWorkerGateway = new TwoWorkerQueryGateway();
        PaymentApplicationService queryService = applicationService(twoWorkerGateway, mock(AuditService.class));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> staleWorker = executor.submit(queryService::queryTimedOutPayments);
            twoWorkerGateway.awaitFirstQuery();
            jdbcTemplate.update(
                    "UPDATE payment_order SET query_lease_expires_at = ? WHERE id = 970451",
                    LocalDateTime.now().minusMinutes(1));
            Future<Integer> newWorker = executor.submit(queryService::queryTimedOutPayments);
            twoWorkerGateway.awaitSecondQuery();
            twoWorkerGateway.releaseFirstQuery();
            assertThat(staleWorker.get(10, TimeUnit.SECONDS)).isZero();
            assertThat(jdbcTemplate.queryForMap("""
                            SELECT status, query_attempt_count
                            FROM payment_order WHERE id = 970451
                            """))
                    .containsEntry("status", "PENDING")
                    .containsEntry("query_attempt_count", 2);
            twoWorkerGateway.releaseSecondQuery();
            assertThat(newWorker.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        } finally {
            twoWorkerGateway.releaseFirstQuery();
            twoWorkerGateway.releaseSecondQuery();
            executor.shutdownNow();
        }
        assertThat(twoWorkerGateway.queryCalls()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM payment_order WHERE id = 970451", String.class))
                .isEqualTo("PAID");
    }

    @Test
    void scheduledRecoveryContinuesToTenantTwoAndHandlesPaymentRefundAndPendingAudit() {
        allowOrder(970_020L, 1L);
        allowOrder(970_021L, 2L);
        allowOrder(970_022L, 2L);
        allowOrder(970_023L, 2L);
        paymentOrderRepository.saveAndFlush(reservedPayment(
                970_400L,
                970_020L,
                "PAY970400",
                "tenant-one-failure",
                1L,
                LocalDateTime.now().minusMinutes(1)));
        paymentOrderRepository.saveAndFlush(reservedPayment(
                970_401L,
                970_021L,
                "PAY970401",
                "tenant-two-payment",
                2L,
                LocalDateTime.now().minusMinutes(1)));
        paymentOrderRepository.saveAndFlush(paidPayment(970_402L, 970_022L, "PAY970402", 2L, BigDecimal.ZERO));
        paymentOrderRepository.saveAndFlush(paidPayment(970_403L, 970_023L, "PAY970403", 2L, new BigDecimal("5.00")));
        paymentLedgerRepository.saveAndFlush(
                expiredRefund(970_500L, 970_402L, 970_022L, "tenant-two-refund", new BigDecimal("10.00"), 2L));
        paymentLedgerRepository.saveAndFlush(
                pendingRefundAudit(970_501L, 970_403L, 970_023L, "tenant-two-audit", new BigDecimal("5.00"), 2L));
        gateway.failedCreatePaymentNo = "PAY970400";
        service = applicationService(gateway, new AuditService(new JpaAuditLogStore(auditLogRepository), 180));
        TenantContext.setTenantId(77L);

        service.recoverExpiredOperationsScheduled();

        assertThat(TenantContext.currentTenantId()).contains(77L);
        assertThat(jdbcTemplate.queryForMap(
                        "SELECT operation_state, attempt_count FROM payment_order WHERE id = 970400"))
                .containsEntry("operation_state", "RETRYABLE")
                .containsEntry("attempt_count", 2);
        assertThat(jdbcTemplate.queryForMap(
                        "SELECT operation_state, attempt_count FROM payment_order WHERE id = 970401"))
                .containsEntry("operation_state", "COMPLETED")
                .containsEntry("attempt_count", 2);
        assertThat(jdbcTemplate.queryForMap("""
                        SELECT operation_state, attempt_count, status, audit_state
                        FROM payment_ledger WHERE id = 970500
                        """))
                .containsEntry("operation_state", "COMPLETED")
                .containsEntry("attempt_count", 2)
                .containsEntry("status", "SUCCESS")
                .containsEntry("audit_state", "DELIVERED");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT refunded_amount FROM payment_order WHERE id = 970402", BigDecimal.class))
                .isEqualByComparingTo("10.00");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT audit_state FROM payment_ledger WHERE id = 970501", String.class))
                .isEqualTo("DELIVERED");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT refunded_amount FROM payment_order WHERE id = 970403", BigDecimal.class))
                .isEqualByComparingTo("5.00");
        assertThat(gateway.createTokens).contains("PAY970400", "PAY970401");
        assertThat(gateway.refundTokens).containsExactly("tenant-two-refund");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM audit_log WHERE event_type = 'PAYMENT_REFUNDED'", Long.class))
                .isEqualTo(2L);
    }

    @Test
    void terminalRefundReleasesReservedAmount() {
        allowOrder(970_030L, 1L);
        paymentOrderRepository.saveAndFlush(paidPayment(970_410L, 970_030L, "PAY970410", 1L, BigDecimal.ZERO));
        gateway.refundFailure = PaymentGatewayException.rejected("REFUND_DECLINED", "raw decline");

        Throwable failure = catchThrowable(() -> service.refund(
                USER,
                new PaymentRefundRequestDto("PAY970410", new BigDecimal("30.00"), "declined"),
                "terminal-refund"));

        assertThat(failure).isInstanceOf(PaymentGatewayException.class);
        assertThat(jdbcTemplate.queryForMap("""
                        SELECT status, operation_state, terminal_failure_code, lease_expires_at
                        FROM payment_ledger
                        WHERE payment_id = 970410 AND request_key = 'terminal-refund'
                        """))
                .containsEntry("status", "FAILED")
                .containsEntry("operation_state", "TERMINAL_FAILED")
                .containsEntry("terminal_failure_code", "REFUND_DECLINED")
                .containsEntry("lease_expires_at", null);
        assertThat(jdbcTemplate.queryForObject("""
                        SELECT COALESCE(SUM(amount), 0)
                        FROM payment_ledger
                        WHERE payment_id = 970410 AND ledger_type = 'REFUND' AND status = 'ACCEPTED'
                        """, BigDecimal.class)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT refunded_amount FROM payment_order WHERE id = 970410", BigDecimal.class))
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(gateway.refundTokens).containsExactly("PAY970410:refund:970100");
    }

    @Test
    void auditInsertRollbackReplaysExactlyOnceWithoutDuplicateRefund() {
        allowOrder(970_031L, 1L);
        paymentOrderRepository.saveAndFlush(paidPayment(970_420L, 970_031L, "PAY970420", 1L, BigDecimal.ZERO));
        FailAfterInsertAuditService auditService = new FailAfterInsertAuditService(auditLogRepository);
        service = applicationService(gateway, auditService);
        PaymentRefundRequestDto request = new PaymentRefundRequestDto("PAY970420", new BigDecimal("30.00"), "accepted");

        service.refund(USER, request, "audit-replay");

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT refunded_amount FROM payment_order WHERE id = 970420", BigDecimal.class))
                .isEqualByComparingTo("30.00");
        assertThat(jdbcTemplate.queryForMap("""
                        SELECT status, operation_state, attempt_count, audit_state,
                               response_refunded_amount, response_payment_status, response_ledger_status
                        FROM payment_ledger
                        WHERE payment_id = 970420 AND request_key = 'audit-replay'
                        """))
                .containsEntry("status", "SUCCESS")
                .containsEntry("operation_state", "COMPLETED")
                .containsEntry("attempt_count", 1)
                .containsEntry("audit_state", "PENDING")
                .containsEntry("response_payment_status", "PARTIALLY_REFUNDED")
                .containsEntry("response_ledger_status", "SUCCESS");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_log", Long.class))
                .isZero();

        service.recoverExpiredOperations();
        service.recoverExpiredOperations();

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT audit_state FROM payment_ledger WHERE payment_id = 970420 AND request_key = 'audit-replay'",
                        String.class))
                .isEqualTo("DELIVERED");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT refunded_amount FROM payment_order WHERE id = 970420", BigDecimal.class))
                .isEqualByComparingTo("30.00");
        assertThat(jdbcTemplate.queryForObject("""
                        SELECT COUNT(*)
                        FROM payment_ledger
                        WHERE payment_id = 970420 AND request_key = 'audit-replay'
                          AND status = 'SUCCESS' AND amount = 30.00
                        """, Long.class)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM audit_log WHERE event_type = 'PAYMENT_REFUNDED'", Long.class))
                .isEqualTo(1L);
        assertThat(gateway.refundTokens).containsExactly("PAY970420:refund:970100");
        assertThat(auditService.deliveryAttempts()).isEqualTo(2);
    }

    private void assertStalePaymentWorkerCannotOverwriteNewerAttempt(
            RuntimeException staleFailure, long paymentId, long orderId, String paymentNo) throws Exception {
        allowOrder(orderId, 1L);
        paymentOrderRepository.saveAndFlush(reservedPayment(
                paymentId,
                orderId,
                paymentNo,
                "stale-" + paymentId,
                1L,
                LocalDateTime.now().minusMinutes(1)));
        TwoWorkerGateway twoWorkerGateway = new TwoWorkerGateway(staleFailure);
        PaymentApplicationService recoveryService = applicationService(twoWorkerGateway, mock(AuditService.class));
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Integer> staleWorker = executor.submit(recoveryService::recoverExpiredOperations);
            twoWorkerGateway.awaitFirstCreate();
            jdbcTemplate.update(
                    "UPDATE payment_order SET lease_expires_at = ? WHERE id = ?",
                    LocalDateTime.now().minusMinutes(1),
                    paymentId);
            Future<Integer> newWorker = executor.submit(recoveryService::recoverExpiredOperations);
            twoWorkerGateway.awaitSecondCreate();

            twoWorkerGateway.releaseFirstCreate();
            assertThat(staleWorker.get(10, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForMap("""
                            SELECT status, operation_state, attempt_count,
                                   last_failure_classification, terminal_failure_code
                            FROM payment_order WHERE id = ?
                            """, paymentId))
                    .containsEntry("status", "PENDING")
                    .containsEntry("operation_state", "RESERVED")
                    .containsEntry("attempt_count", 3)
                    .containsEntry("last_failure_classification", "NONE")
                    .containsEntry("terminal_failure_code", null);

            twoWorkerGateway.releaseSecondCreate();
            assertThat(newWorker.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        } finally {
            twoWorkerGateway.releaseFirstCreate();
            twoWorkerGateway.releaseSecondCreate();
            executor.shutdownNow();
        }

        assertThat(jdbcTemplate.queryForMap(
                        "SELECT operation_state, attempt_count FROM payment_order WHERE id = ?", paymentId))
                .containsEntry("operation_state", "COMPLETED")
                .containsEntry("attempt_count", 3);
        assertThat(twoWorkerGateway.createCalls()).isEqualTo(2);
    }

    private PausingPaymentTransactions pausingTransactions(int commitNumber) {
        PausingPaymentTransactions transactions =
                new PausingPaymentTransactions(new RequiresNewPaymentTransactions(transactionManager));
        transactions.pauseAfterCommit(commitNumber);
        return transactions;
    }

    private PaymentOperationAttempt claimPaymentTakeover(String paymentNo, Clock clock) {
        JpaPaymentStore store = paymentStore();
        return new RequiresNewPaymentTransactions(transactionManager)
                .execute(() -> store.withLockedPayment(paymentNo, ignored -> {
                            PaymentStore.PaymentIntent latest = store.findPaymentIntentByPaymentNo(paymentNo)
                                    .orElseThrow();
                            return store.savePayment(
                                            latest.payment(),
                                            latest.requestFingerprint(),
                                            latest.operation().claim(now(clock), Duration.ofMinutes(2)),
                                            latest.merchantToken(),
                                            latest.responseSnapshot())
                                    .operation();
                        })
                        .orElseThrow());
    }

    private PaymentOperationAttempt claimRefundTakeover(String paymentNo, String requestKey, Clock clock) {
        JpaPaymentStore store = paymentStore();
        return new RequiresNewPaymentTransactions(transactionManager)
                .execute(() -> store.withLockedPayment(paymentNo, payment -> {
                            PaymentStore.RefundRequest latest = store.findRefundRequest(payment.id(), requestKey)
                                    .orElseThrow();
                            return store.saveLedger(
                                            latest.ledger(),
                                            latest.requestFingerprint(),
                                            latest.operation().claim(now(clock), Duration.ofMinutes(2)),
                                            latest.merchantToken(),
                                            latest.responseSnapshot(),
                                            latest.auditIntent())
                                    .operation();
                        })
                        .orElseThrow());
    }

    private PaymentQueryAttempt claimQueryTakeover(String paymentNo, Clock clock) {
        JpaPaymentStore store = paymentStore();
        return new RequiresNewPaymentTransactions(transactionManager)
                .execute(() -> store.withLockedPayment(paymentNo, payment -> {
                            PaymentStore.PaymentIntent latest = store.findPaymentIntentByPaymentNo(paymentNo)
                                    .orElseThrow();
                            return store.savePaymentQueryAttempt(
                                            payment, latest.queryAttempt().claim(now(clock), Duration.ofMinutes(2)))
                                    .queryAttempt();
                        })
                        .orElseThrow());
    }

    private JpaPaymentStore paymentStore() {
        return new JpaPaymentStore(
                paymentOrderRepository, paymentLedgerRepository, reconciliationReportRepository, piiCryptoService);
    }

    private static LocalDateTime now(Clock clock) {
        return LocalDateTime.ofInstant(clock.instant(), clock.getZone());
    }

    private static Throwable awaitWorkerFailure(Future<?> worker) throws Exception {
        try {
            worker.get(10, TimeUnit.SECONDS);
            return null;
        } catch (ExecutionException exception) {
            return exception.getCause();
        }
    }

    private PaymentApplicationService applicationService(PaymentGateway paymentGateway, AuditService auditService) {
        return applicationService(
                paymentGateway,
                auditService,
                new RequiresNewPaymentTransactions(transactionManager),
                Clock.systemDefaultZone());
    }

    private PaymentApplicationService applicationService(
            PaymentGateway paymentGateway,
            AuditService auditService,
            PaymentTransactions paymentTransactions,
            Clock clock) {
        OrderStore orderStore = mock(OrderStore.class);
        when(orderStore.findVisibleByIdAndUserId(anyLong(), anyLong())).thenAnswer(invocation -> {
            OrderRecord order = visibleOrders.get(invocation.getArgument(0, Long.class));
            Long userId = invocation.getArgument(1, Long.class);
            return order != null && order.userId().equals(userId) ? Optional.of(order) : Optional.empty();
        });
        JpaPaymentStore store = paymentStore();
        PaymentCallbackReplayGuard replayGuard = (provider, paymentNo, callbackId, ttl) -> true;
        PaymentTransitionResolver resolver = (status, event) -> PaymentTransitionPolicy.nextStatus(status, event)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT, "invalid transition"));
        IdGenerator idGenerator = ids::getAndIncrement;
        return new PaymentApplicationService(
                store,
                paymentGateway,
                replayGuard,
                resolver,
                orderStore,
                mock(UserAccountStore.class),
                mock(UserMfaVerifier.class),
                idGenerator,
                auditService,
                paymentTransactions,
                new JdbcPaymentRecoveryTenantSource(jdbcTemplate),
                clock,
                Duration.ofHours(24),
                Duration.ofMinutes(5),
                Duration.ofMinutes(2),
                Duration.ofSeconds(30),
                new BigDecimal("5000.00"),
                "local-mysql-acceptance-secret");
    }

    private void allowOrder(Long orderId, Long tenantId) {
        jdbcTemplate.update(
                "INSERT INTO orders (id, order_no, user_id, price, status, tenant_id) VALUES (?, ?, ?, ?, ?, ?)",
                orderId,
                "ORD" + orderId,
                42L,
                new BigDecimal("100.00"),
                "PAID",
                tenantId);
        visibleOrders.put(orderId, order(orderId));
    }

    private static int recoverAfter(CountDownLatch start, PaymentApplicationService service) throws Exception {
        start.await(10, TimeUnit.SECONDS);
        return service.recoverExpiredOperations();
    }

    private static OrderRecord order(Long orderId) {
        return new OrderRecord(
                orderId,
                "ORD" + orderId,
                42L,
                "buyer",
                null,
                7L,
                "Momo",
                null,
                new BigDecimal("100.00"),
                "payment fixture",
                "Ada",
                "13800138000",
                "Hangzhou",
                null,
                "PAID",
                LocalDateTime.parse("2026-07-04T08:00:00"),
                false);
    }

    private static PaymentOrderEntity reservedPayment(
            Long id,
            Long orderId,
            String paymentNo,
            String idempotencyKey,
            Long tenantId,
            LocalDateTime leaseExpiresAt) {
        LocalDateTime now = LocalDateTime.now();
        PaymentOrderEntity entity = new PaymentOrderEntity();
        entity.setId(id);
        entity.setTenantId(tenantId);
        entity.setPaymentNo(paymentNo);
        entity.setOrderId(orderId);
        entity.setUserId(42L);
        entity.setMethod(PaymentMethod.WECHAT);
        entity.setAmount(new BigDecimal("100.00"));
        entity.setPaidAmount(BigDecimal.ZERO.setScale(2));
        entity.setRefundedAmount(BigDecimal.ZERO.setScale(2));
        entity.setStatus(PaymentStatus.PENDING);
        entity.setIdempotencyKey(idempotencyKey);
        entity.setRequestFingerprint("a".repeat(64));
        entity.setOperationState(PaymentOperationState.RESERVED);
        entity.setAttemptCount(1);
        entity.setLeaseExpiresAt(leaseExpiresAt);
        entity.setLastFailureClassification(PaymentFailureClassification.NONE);
        entity.setMerchantToken(paymentNo);
        entity.setCreateTime(now.minusMinutes(5));
        entity.setUpdateTime(now.minusMinutes(5));
        return entity;
    }

    private static PaymentOrderEntity queryReadyPayment(Long id, Long orderId, String paymentNo, Long tenantId) {
        PaymentOrderEntity entity = reservedPayment(
                id,
                orderId,
                paymentNo,
                "query-" + id,
                tenantId,
                LocalDateTime.now().minusMinutes(1));
        entity.setOperationState(PaymentOperationState.COMPLETED);
        entity.setLeaseExpiresAt(null);
        entity.setProviderTradeNo("LOCAL-PREPAY-" + paymentNo);
        entity.setResponsePaidAmount(BigDecimal.ZERO.setScale(2));
        entity.setResponseRefundedAmount(BigDecimal.ZERO.setScale(2));
        entity.setResponseStatus(PaymentStatus.PENDING);
        entity.setResponseProviderTradeNo("LOCAL-PREPAY-" + paymentNo);
        entity.setQueryAttemptCount(0);
        entity.setQueryLeaseExpiresAt(null);
        entity.setNextQueryAt(LocalDateTime.now().minusMinutes(1));
        return entity;
    }

    private static PaymentOrderEntity legacyQueryReadyPayment(
            Long id,
            Long orderId,
            String paymentNo,
            String idempotencyKey,
            String requestFingerprint,
            LocalDateTime nextQueryAt) {
        PaymentOrderEntity entity = reservedPayment(
                id, orderId, paymentNo, idempotencyKey, 1L, LocalDateTime.now().minusMinutes(1));
        entity.setRequestFingerprint(requestFingerprint);
        entity.setOperationState(PaymentOperationState.LEGACY_UNREPLAYABLE);
        entity.setAttemptCount(0);
        entity.setLeaseExpiresAt(null);
        entity.setLastFailureClassification(PaymentFailureClassification.LEGACY_UNKNOWN);
        entity.setMerchantToken(null);
        entity.setResponsePaidAmount(null);
        entity.setResponseRefundedAmount(null);
        entity.setResponseStatus(null);
        entity.setResponseProviderTradeNo(null);
        entity.setResponsePaidAt(null);
        entity.setQueryAttemptCount(0);
        entity.setQueryLeaseExpiresAt(null);
        entity.setNextQueryAt(nextQueryAt);
        return entity;
    }

    private static LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime;
        }
        return ((Timestamp) value).toLocalDateTime();
    }

    private static PaymentOrderEntity paidPayment(
            Long id, Long orderId, String paymentNo, Long tenantId, BigDecimal refundedAmount) {
        PaymentOrderEntity entity = reservedPayment(
                id,
                orderId,
                paymentNo,
                "paid-" + id,
                tenantId,
                LocalDateTime.now().minusMinutes(1));
        LocalDateTime paidAt = LocalDateTime.now().minusMinutes(10);
        entity.setPaidAmount(new BigDecimal("100.00"));
        entity.setRefundedAmount(refundedAmount);
        entity.setStatus(refundedAmount.signum() == 0 ? PaymentStatus.PAID : PaymentStatus.PARTIALLY_REFUNDED);
        entity.setOperationState(PaymentOperationState.COMPLETED);
        entity.setLeaseExpiresAt(null);
        entity.setProviderTradeNo("MYSQL-PAID-" + paymentNo);
        entity.setPaidAt(paidAt);
        entity.setResponsePaidAmount(new BigDecimal("100.00"));
        entity.setResponseRefundedAmount(BigDecimal.ZERO.setScale(2));
        entity.setResponseStatus(PaymentStatus.PAID);
        entity.setResponseProviderTradeNo("MYSQL-PAID-" + paymentNo);
        entity.setResponsePaidAt(paidAt);
        return entity;
    }

    private static PaymentLedgerEntity expiredRefund(
            Long id, Long paymentId, Long orderId, String requestKey, BigDecimal amount, Long tenantId) {
        PaymentLedgerEntity entity = refundLedger(id, paymentId, orderId, requestKey, amount, tenantId);
        entity.setStatus(PaymentLedgerStatus.ACCEPTED);
        entity.setOperationState(PaymentOperationState.RESERVED);
        entity.setAttemptCount(1);
        entity.setLeaseExpiresAt(LocalDateTime.now().minusMinutes(1));
        entity.setAuditState(RefundAuditState.WAITING);
        return entity;
    }

    private static PaymentLedgerEntity pendingRefundAudit(
            Long id, Long paymentId, Long orderId, String requestKey, BigDecimal amount, Long tenantId) {
        PaymentLedgerEntity entity = refundLedger(id, paymentId, orderId, requestKey, amount, tenantId);
        entity.setStatus(PaymentLedgerStatus.SUCCESS);
        entity.setOperationState(PaymentOperationState.COMPLETED);
        entity.setAttemptCount(1);
        entity.setLeaseExpiresAt(null);
        entity.setProviderTradeNo("MYSQL-RF-" + requestKey);
        entity.setResponseRefundedAmount(amount);
        entity.setResponsePaymentStatus(PaymentStatus.PARTIALLY_REFUNDED);
        entity.setResponseLedgerStatus(PaymentLedgerStatus.SUCCESS);
        entity.setAuditState(RefundAuditState.PENDING);
        entity.setAuditDetail("amount=" + amount + ",status=PARTIALLY_REFUNDED");
        return entity;
    }

    private static PaymentLedgerEntity refundLedger(
            Long id, Long paymentId, Long orderId, String requestKey, BigDecimal amount, Long tenantId) {
        PaymentLedgerEntity entity = new PaymentLedgerEntity();
        entity.setId(id);
        entity.setTenantId(tenantId);
        entity.setPaymentId(paymentId);
        entity.setOrderId(orderId);
        entity.setUserId(42L);
        entity.setLedgerType(PaymentLedgerType.REFUND);
        entity.setAmount(amount);
        entity.setRequestKey(requestKey);
        entity.setRequestFingerprint("b".repeat(64));
        entity.setLastFailureClassification(PaymentFailureClassification.NONE);
        entity.setMerchantToken(requestKey);
        entity.setAuditEventType(AuditService.PAYMENT_REFUNDED);
        entity.setAuditActorUserId(42L);
        entity.setAuditActorRole("USER");
        entity.setAuditSourceIp(null);
        entity.setAuditIncludeOwner(true);
        entity.setCreateTime(LocalDateTime.now().minusMinutes(5));
        return entity;
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required system property " + name);
        }
        return value;
    }

    private static String causeMessages(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            messages.append(cause.getMessage()).append('\n');
        }
        return messages.toString();
    }

    private static final class RecordingGateway implements PaymentGateway {

        private final List<String> createTokens = new CopyOnWriteArrayList<>();
        private final List<String> refundTokens = new CopyOnWriteArrayList<>();
        private final List<String> queryPaymentNos = new CopyOnWriteArrayList<>();
        private String failedCreatePaymentNo;
        private RuntimeException refundFailure;
        private PaymentGatewayResult queryResult;

        @Override
        public PaymentGatewayResult create(PaymentOrder payment, String merchantToken) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isFalse();
            createTokens.add(merchantToken);
            if (payment.paymentNo().equals(failedCreatePaymentNo)) {
                throw new IllegalStateException("tenant one gateway failed");
            }
            return new PaymentGatewayResult(
                    PaymentStatus.PENDING,
                    "MYSQL-" + merchantToken,
                    "/mysql/payments/" + payment.paymentNo(),
                    payment.amount());
        }

        @Override
        public PaymentGatewayResult query(PaymentOrder payment) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isFalse();
            queryPaymentNos.add(payment.paymentNo());
            return queryResult == null
                    ? new PaymentGatewayResult(PaymentStatus.PENDING, null, null, payment.amount())
                    : queryResult;
        }

        @Override
        public PaymentGatewayResult refund(PaymentOrder payment, BigDecimal amount, String merchantToken) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isFalse();
            refundTokens.add(merchantToken);
            if (refundFailure != null) {
                throw refundFailure;
            }
            return new PaymentGatewayResult(
                    PaymentStatus.PARTIALLY_REFUNDED, "MYSQL-RF-" + merchantToken, null, amount);
        }
    }

    private static final class BlockingGateway implements PaymentGateway {

        private final AtomicInteger createCalls = new AtomicInteger();
        private final CountDownLatch createEntered = new CountDownLatch(1);
        private final CountDownLatch releaseCreate = new CountDownLatch(1);

        @Override
        public PaymentGatewayResult create(PaymentOrder payment, String merchantToken) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isFalse();
            createCalls.incrementAndGet();
            createEntered.countDown();
            await(releaseCreate, "release local MySQL create");
            return new PaymentGatewayResult(
                    PaymentStatus.PENDING,
                    "MYSQL-" + merchantToken,
                    "/mysql/payments/" + payment.paymentNo(),
                    payment.amount());
        }

        @Override
        public PaymentGatewayResult query(PaymentOrder payment) {
            return new PaymentGatewayResult(PaymentStatus.PENDING, null, null, payment.amount());
        }

        @Override
        public PaymentGatewayResult refund(PaymentOrder payment, BigDecimal amount, String merchantToken) {
            return new PaymentGatewayResult(
                    PaymentStatus.PARTIALLY_REFUNDED, "MYSQL-RF-" + merchantToken, null, amount);
        }

        private void awaitCreate() {
            await(createEntered, "local MySQL create");
        }

        private void releaseCreate() {
            releaseCreate.countDown();
        }

        private int createCalls() {
            return createCalls.get();
        }
    }

    private static final class TwoWorkerGateway implements PaymentGateway {

        private final RuntimeException firstFailure;
        private final AtomicInteger createCalls = new AtomicInteger();
        private final CountDownLatch firstCreateEntered = new CountDownLatch(1);
        private final CountDownLatch releaseFirstCreate = new CountDownLatch(1);
        private final CountDownLatch secondCreateEntered = new CountDownLatch(1);
        private final CountDownLatch releaseSecondCreate = new CountDownLatch(1);

        private TwoWorkerGateway(RuntimeException firstFailure) {
            this.firstFailure = firstFailure;
        }

        @Override
        public PaymentGatewayResult create(PaymentOrder payment, String merchantToken) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isFalse();
            int call = createCalls.incrementAndGet();
            if (call == 1) {
                firstCreateEntered.countDown();
                await(releaseFirstCreate, "release stale local MySQL create");
                if (firstFailure != null) {
                    throw firstFailure;
                }
            } else if (call == 2) {
                secondCreateEntered.countDown();
                await(releaseSecondCreate, "release new local MySQL create");
            }
            return new PaymentGatewayResult(
                    PaymentStatus.PENDING,
                    "MYSQL-" + merchantToken,
                    "/mysql/payments/" + payment.paymentNo(),
                    payment.amount());
        }

        @Override
        public PaymentGatewayResult query(PaymentOrder payment) {
            return new PaymentGatewayResult(PaymentStatus.PENDING, null, null, payment.amount());
        }

        @Override
        public PaymentGatewayResult refund(PaymentOrder payment, BigDecimal amount, String merchantToken) {
            return new PaymentGatewayResult(
                    PaymentStatus.PARTIALLY_REFUNDED, "MYSQL-RF-" + merchantToken, null, amount);
        }

        private void awaitFirstCreate() {
            await(firstCreateEntered, "stale local MySQL create");
        }

        private void awaitSecondCreate() {
            await(secondCreateEntered, "new local MySQL create");
        }

        private void releaseFirstCreate() {
            releaseFirstCreate.countDown();
        }

        private void releaseSecondCreate() {
            releaseSecondCreate.countDown();
        }

        private int createCalls() {
            return createCalls.get();
        }
    }

    private static final class BlockingQueryGateway implements PaymentGateway {

        private final AtomicInteger queryCalls = new AtomicInteger();
        private final CountDownLatch queryEntered = new CountDownLatch(1);
        private final CountDownLatch releaseQuery = new CountDownLatch(1);

        @Override
        public PaymentGatewayResult create(PaymentOrder payment, String merchantToken) {
            throw new AssertionError("create is not expected");
        }

        @Override
        public PaymentGatewayResult query(PaymentOrder payment) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isFalse();
            queryCalls.incrementAndGet();
            queryEntered.countDown();
            await(releaseQuery, "release local MySQL query");
            return new PaymentGatewayResult(PaymentStatus.PENDING, null, null, payment.amount());
        }

        @Override
        public PaymentGatewayResult refund(PaymentOrder payment, BigDecimal amount, String merchantToken) {
            throw new AssertionError("refund is not expected");
        }

        private void awaitQuery() {
            await(queryEntered, "local MySQL query");
        }

        private void releaseQuery() {
            releaseQuery.countDown();
        }

        private int queryCalls() {
            return queryCalls.get();
        }
    }

    private static final class TwoWorkerQueryGateway implements PaymentGateway {

        private final AtomicInteger queryCalls = new AtomicInteger();
        private final CountDownLatch firstQueryEntered = new CountDownLatch(1);
        private final CountDownLatch releaseFirstQuery = new CountDownLatch(1);
        private final CountDownLatch secondQueryEntered = new CountDownLatch(1);
        private final CountDownLatch releaseSecondQuery = new CountDownLatch(1);

        @Override
        public PaymentGatewayResult create(PaymentOrder payment, String merchantToken) {
            throw new AssertionError("create is not expected");
        }

        @Override
        public PaymentGatewayResult query(PaymentOrder payment) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isFalse();
            int call = queryCalls.incrementAndGet();
            if (call == 1) {
                firstQueryEntered.countDown();
                await(releaseFirstQuery, "release stale local MySQL query");
                return new PaymentGatewayResult(PaymentStatus.FAILED, null, null, BigDecimal.ZERO);
            }
            if (call == 2) {
                secondQueryEntered.countDown();
                await(releaseSecondQuery, "release new local MySQL query");
                return new PaymentGatewayResult(PaymentStatus.PAID, "LOCAL-QUERY-PAID", null, payment.amount());
            }
            throw new AssertionError("unexpected query invocation " + call);
        }

        @Override
        public PaymentGatewayResult refund(PaymentOrder payment, BigDecimal amount, String merchantToken) {
            throw new AssertionError("refund is not expected");
        }

        private void awaitFirstQuery() {
            await(firstQueryEntered, "stale local MySQL query");
        }

        private void awaitSecondQuery() {
            await(secondQueryEntered, "new local MySQL query");
        }

        private void releaseFirstQuery() {
            releaseFirstQuery.countDown();
        }

        private void releaseSecondQuery() {
            releaseSecondQuery.countDown();
        }

        private int queryCalls() {
            return queryCalls.get();
        }
    }

    private static final class PausingPaymentTransactions implements PaymentTransactions {

        private final PaymentTransactions delegate;
        private final AtomicInteger committedTransactions = new AtomicInteger();
        private volatile int pauseAfterCommit = -1;
        private volatile CountDownLatch pausedCommit;
        private volatile CountDownLatch releaseCommit;

        private PausingPaymentTransactions(PaymentTransactions delegate) {
            this.delegate = delegate;
        }

        private void pauseAfterCommit(int commitNumber) {
            pauseAfterCommit = commitNumber;
            pausedCommit = new CountDownLatch(1);
            releaseCommit = new CountDownLatch(1);
        }

        private void awaitPausedCommit() {
            await(pausedCommit, "committed local MySQL transaction pause");
        }

        private void releasePausedCommit() {
            CountDownLatch release = releaseCommit;
            if (release != null) {
                release.countDown();
            }
        }

        @Override
        public <T> T execute(java.util.function.Supplier<T> action) {
            T result = delegate.execute(action);
            if (committedTransactions.incrementAndGet() == pauseAfterCommit) {
                pausedCommit.countDown();
                await(releaseCommit, "release committed local MySQL transaction");
            }
            return result;
        }
    }

    private static final class MutableClock extends Clock {

        private volatile Instant current;
        private final ZoneId zone;

        private MutableClock(Instant current, ZoneId zone) {
            this.current = current;
            this.zone = zone;
        }

        private void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId targetZone) {
            return new MutableClock(current, targetZone);
        }

        @Override
        public Instant instant() {
            return current;
        }
    }

    private static final class FailAfterInsertAuditService extends AuditService {

        private final AuditLogRepository auditLogRepository;
        private final AtomicInteger deliveryAttempts = new AtomicInteger();

        private FailAfterInsertAuditService(AuditLogRepository auditLogRepository) {
            super(new JpaAuditLogStore(auditLogRepository), 180);
            this.auditLogRepository = auditLogRepository;
        }

        @Override
        public void recordReliable(
                String eventType,
                String outcome,
                Long actorUserId,
                String actorRole,
                String subject,
                String sourceIp,
                String detail) {
            super.recordReliable(eventType, outcome, actorUserId, actorRole, subject, sourceIp, detail);
            auditLogRepository.flush();
            if (deliveryAttempts.incrementAndGet() == 1) {
                throw new IllegalStateException("fail after durable audit insert");
            }
        }

        private int deliveryAttempts() {
            return deliveryAttempts.get();
        }
    }

    private static void await(CountDownLatch latch, String description) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for " + description);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted waiting for " + description, exception);
        }
    }
}
