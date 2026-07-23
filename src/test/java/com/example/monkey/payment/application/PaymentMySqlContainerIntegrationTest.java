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
import com.example.monkey.shared.domain.inventory.InventoryReservationLifecycle;
import com.example.monkey.shared.infrastructure.observability.AuditLogRepository;
import com.example.monkey.shared.infrastructure.observability.JpaAuditLogStore;
import com.example.monkey.shared.infrastructure.privacy.PiiCryptoService;
import com.example.monkey.user.domain.UserAccountStore;
import com.example.monkey.user.domain.UserMfaVerifier;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@TestPropertySource(
        properties = {
            "spring.flyway.enabled=true",
            "spring.jpa.hibernate.ddl-auto=validate",
            "app.payment.callback-secret=testcontainers-secret"
        })
@MockitoBean(types = PiiCryptoService.class)
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentMySqlContainerIntegrationTest {

    private static final String MYSQL_IMAGE = "mysql:8.0.41";
    private static final SessionUser USER = new SessionUser(42L, "USER");

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(MYSQL_IMAGE)
            .withDatabaseName("monkeyshop_task4")
            .withUsername("monkey")
            .withPassword("monkey");

    private final PaymentOrderRepository paymentOrderRepository;
    private final PaymentLedgerRepository paymentLedgerRepository;
    private final PaymentReconciliationReportRepository reconciliationReportRepository;
    private final PiiCryptoService piiCryptoService;
    private final AuditLogRepository auditLogRepository;
    private final PlatformTransactionManager transactionManager;
    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;
    private final Map<Long, OrderRecord> visibleOrders = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong(960_100L);
    private RecordingGateway gateway;
    private PaymentApplicationService service;
    private boolean restoreSchemaAfterTest;

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @Autowired
    PaymentMySqlContainerIntegrationTest(
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
        this.dataSource = dataSource;
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(1L);
        jdbcTemplate.update("DELETE FROM audit_log");
        jdbcTemplate.update("DELETE FROM payment_ledger");
        jdbcTemplate.update("DELETE FROM payment_order");
        jdbcTemplate.update("DELETE FROM orders WHERE id >= 960000");
        jdbcTemplate.update("""
                INSERT INTO tenant (id, code, name, status, plan, expires_at)
                VALUES (2, 'task4-second', 'Task 4 Second Tenant', 'ACTIVE', 'STANDARD', '2099-12-31 23:59:59')
                ON DUPLICATE KEY UPDATE name = VALUES(name)
                """);
        visibleOrders.clear();
        ids.set(960_100L);
        gateway = new RecordingGateway();
        service = applicationService(gateway);
    }

    @AfterEach
    void clearTenant() {
        try {
            if (restoreSchemaAfterTest) {
                restoreSchemaAtV51();
            }
        } finally {
            restoreSchemaAfterTest = false;
            TenantContext.clear();
        }
    }

    @Test
    void sameKeySameFingerprintConvergesOnOneRowAndStableGatewayToken() throws Exception {
        allowOrder(960_001L, 1L);

        List<Object> outcomes =
                invokeConcurrently(List.of(() -> create(960_001L, "same-key"), () -> create(960_001L, "same-key")));

        List<PaymentResponseDto> responses = outcomes.stream()
                .filter(PaymentResponseDto.class::isInstance)
                .map(PaymentResponseDto.class::cast)
                .toList();
        List<BusinessException> conflicts = outcomes.stream()
                .filter(BusinessException.class::isInstance)
                .map(BusinessException.class::cast)
                .toList();
        assertThat(responses)
                .isNotEmpty()
                .allSatisfy(response -> assertThat(response.paymentNo())
                        .isEqualTo(responses.getFirst().paymentNo()));
        assertThat(conflicts)
                .allSatisfy(failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        assertThat(responses.size() + conflicts.size()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payment_order", Long.class))
                .isEqualTo(1L);
        assertThat(gateway.createTokens).containsExactly("PAY960100");
    }

    @Test
    void concurrentFingerprintAndActiveOrderCollisionsReturnConflict() throws Exception {
        allowOrder(960_002L, 1L);
        allowOrder(960_003L, 1L);

        List<Object> keyCollision =
                invokeConcurrently(List.of(() -> create(960_002L, "shared-key"), () -> create(960_003L, "shared-key")));
        assertOneSuccessAndOneConflict(keyCollision);

        resetPayments();
        gateway.createTokens.clear();
        ids.set(960_200L);
        List<Object> activeCollision = invokeConcurrently(
                List.of(() -> create(960_002L, "active-key-one"), () -> create(960_002L, "active-key-two")));
        assertOneSuccessAndOneConflict(activeCollision);
        assertThat(gateway.createTokens).hasSize(1);
    }

    @Test
    void sameOwnerKeyIsIndependentAcrossTenants() {
        allowOrder(960_004L, 1L);
        allowOrder(960_005L, 2L);

        TenantContext.setTenantId(1L);
        PaymentResponseDto first = create(960_004L, "cross-tenant-key");
        TenantContext.setTenantId(2L);
        PaymentResponseDto second = create(960_005L, "cross-tenant-key");

        assertThat(second.paymentNo()).isNotEqualTo(first.paymentNo());
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM payment_order WHERE idempotency_key = 'cross-tenant-key'", Long.class))
                .isEqualTo(2L);
    }

    @Test
    void requiresNewReservationIsVisibleAtGatewayAndSurvivesOuterRollback() {
        allowOrder(960_006L, 1L);
        gateway.createObserver = () -> assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM payment_order WHERE idempotency_key = 'outer-key'", Long.class))
                .isEqualTo(1L);
        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        outer.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);

        Throwable failure = catchThrowable(() -> outer.executeWithoutResult(status -> {
            create(960_006L, "outer-key");
            throw new IllegalStateException("rollback caller");
        }));

        assertThat(failure).isInstanceOf(IllegalStateException.class);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM payment_order WHERE idempotency_key = 'outer-key'", Long.class))
                .isEqualTo(1L);
    }

    @Test
    void concurrentRecoveryClaimsExpiredPaymentOnceWhileLeaseIsValid() throws Exception {
        allowOrder(960_007L, 1L);
        paymentOrderRepository.saveAndFlush(expiredPayment(960_300L, 960_007L, "PAY960300", "recover-once", 1L));
        gateway.blockFirstCreate();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        ExecutorCompletionService<Integer> recoveries = new ExecutorCompletionService<>(executor);
        CountDownLatch start = new CountDownLatch(1);

        try {
            recoveries.submit(() -> recoverAfter(start));
            recoveries.submit(() -> recoverAfter(start));
            start.countDown();
            gateway.awaitFirstCreate();

            Future<Integer> nonWinner = recoveries.poll(10, TimeUnit.SECONDS);
            assertThat(nonWinner).isNotNull();
            assertThat(nonWinner.get(10, TimeUnit.SECONDS)).isZero();

            gateway.releaseFirstCreate();
            assertThat(recoveries.poll(10, TimeUnit.SECONDS).get(10, TimeUnit.SECONDS))
                    .isEqualTo(1);
        } finally {
            gateway.releaseFirstCreate();
            executor.shutdownNow();
        }

        Map<String, Object> operation =
                jdbcTemplate.queryForMap("SELECT operation_state, attempt_count FROM payment_order WHERE id = 960300");
        assertThat(operation.get("operation_state")).isEqualTo("COMPLETED");
        assertThat(((Number) operation.get("attempt_count")).intValue()).isEqualTo(2);
        assertThat(gateway.createTokens).containsExactly("PAY960300");
    }

    @Test
    void stalePaymentCompletionAndTerminalFailureCannotOverwriteNewerAttempt() throws Exception {
        assertStalePaymentWorkerCannotOverwriteNewerAttempt(null, 960_310L, 960_008L, "PAY960310");
        assertStalePaymentWorkerCannotOverwriteNewerAttempt(
                PaymentGatewayException.rejected("CARD_DECLINED", "raw decline"), 960_311L, 960_009L, "PAY960311");
    }

    @Test
    void createReservationTakeoverPreventsStaleWorkerProviderCall() throws Exception {
        allowOrder(960_030L, 1L);
        MutableClock clock = new MutableClock(Instant.now(), ZoneId.systemDefault());
        PausingPaymentTransactions transactions = pausingTransactions(2);
        RecordingGateway staleGateway = new RecordingGateway();
        PaymentApplicationService staleService =
                applicationService(staleGateway, mock(AuditService.class), transactions, clock);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<PaymentResponseDto> staleWorker = executor.submit(() -> staleService.createPayment(
                    USER, new PaymentCreateRequestDto(960_030L, PaymentMethod.WECHAT, null, null), "create-takeover"));
            transactions.awaitPausedCommit();
            Map<String, Object> initial = jdbcTemplate.queryForMap("""
                    SELECT payment_no, operation_state, attempt_count, merchant_token
                    FROM payment_order WHERE idempotency_key = 'create-takeover'
                    """);

            clock.advance(Duration.ofMinutes(3));
            PaymentOperationAttempt newerAttempt =
                    claimPaymentTakeover(initial.get("payment_no").toString(), clock);
            transactions.releasePausedCommit();
            Throwable staleFailure = awaitWorkerFailure(staleWorker);

            assertThat(staleFailure)
                    .isInstanceOfSatisfying(
                            BusinessException.class,
                            failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.CONFLICT));
            assertThat(initial)
                    .containsEntry("operation_state", "RESERVED")
                    .containsEntry("attempt_count", 1)
                    .containsEntry("merchant_token", initial.get("payment_no"));
            assertThat(newerAttempt.attemptCount()).isEqualTo(2);
            assertThat(staleGateway.createTokens).isEmpty();
            assertThat(jdbcTemplate.queryForMap("""
                            SELECT operation_state, attempt_count, merchant_token
                            FROM payment_order WHERE idempotency_key = 'create-takeover'
                            """))
                    .containsEntry("operation_state", "RESERVED")
                    .containsEntry("attempt_count", 2)
                    .containsEntry("merchant_token", initial.get("payment_no"));
        } finally {
            transactions.releasePausedCommit();
            executor.shutdownNow();
        }
    }

    @Test
    void refundReservationTakeoverPreventsStaleWorkerProviderCall() throws Exception {
        allowOrder(960_031L, 1L);
        paymentOrderRepository.saveAndFlush(paidPayment(960_430L, 960_031L, "PAY960430", 1L, BigDecimal.ZERO));
        MutableClock clock = new MutableClock(Instant.now(), ZoneId.systemDefault());
        PausingPaymentTransactions transactions = pausingTransactions(1);
        RecordingGateway staleGateway = new RecordingGateway();
        PaymentApplicationService staleService =
                applicationService(staleGateway, mock(AuditService.class), transactions, clock);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<PaymentRefundResponseDto> staleWorker = executor.submit(() -> staleService.refund(
                    USER,
                    new PaymentRefundRequestDto("PAY960430", new BigDecimal("30.00"), "takeover"),
                    "refund-takeover"));
            transactions.awaitPausedCommit();
            Map<String, Object> initial = jdbcTemplate.queryForMap("""
                    SELECT id, operation_state, attempt_count, merchant_token
                    FROM payment_ledger WHERE payment_id = 960430 AND request_key = 'refund-takeover'
                    """);

            clock.advance(Duration.ofMinutes(3));
            PaymentOperationAttempt newerAttempt = claimRefundTakeover("PAY960430", "refund-takeover", clock);
            transactions.releasePausedCommit();
            Throwable staleFailure = awaitWorkerFailure(staleWorker);

            assertThat(staleFailure)
                    .isInstanceOfSatisfying(
                            BusinessException.class,
                            failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.CONFLICT));
            assertThat(initial).containsEntry("operation_state", "RESERVED").containsEntry("attempt_count", 1);
            assertThat(newerAttempt.attemptCount()).isEqualTo(2);
            assertThat(staleGateway.refundTokens).isEmpty();
            assertThat(jdbcTemplate.queryForMap("""
                            SELECT operation_state, attempt_count, merchant_token
                            FROM payment_ledger WHERE payment_id = 960430 AND request_key = 'refund-takeover'
                            """))
                    .containsEntry("operation_state", "RESERVED")
                    .containsEntry("attempt_count", 2)
                    .containsEntry("merchant_token", initial.get("merchant_token"));
            assertThat(jdbcTemplate.queryForObject(
                            "SELECT refunded_amount FROM payment_order WHERE id = 960430", BigDecimal.class))
                    .isEqualByComparingTo(BigDecimal.ZERO);
        } finally {
            transactions.releasePausedCommit();
            executor.shutdownNow();
        }
    }

    @Test
    void queryClaimTakeoverPreventsStaleWorkerProviderCall() throws Exception {
        allowOrder(960_032L, 1L);
        paymentOrderRepository.saveAndFlush(queryReadyPayment(960_440L, 960_032L, "PAY960440", 1L));
        MutableClock clock = new MutableClock(Instant.now(), ZoneId.systemDefault());
        PausingPaymentTransactions transactions = pausingTransactions(1);
        RecordingGateway staleGateway = new RecordingGateway();
        PaymentApplicationService staleService =
                applicationService(staleGateway, mock(AuditService.class), transactions, clock);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<Integer> staleWorker = executor.submit(staleService::queryTimedOutPayments);
            transactions.awaitPausedCommit();
            Map<String, Object> initial = jdbcTemplate.queryForMap("""
                    SELECT query_attempt_count, query_lease_expires_at
                    FROM payment_order WHERE id = 960440
                    """);

            clock.advance(Duration.ofMinutes(3));
            PaymentQueryAttempt newerAttempt = claimQueryTakeover("PAY960440", clock);
            transactions.releasePausedCommit();

            assertThat(staleWorker.get(10, TimeUnit.SECONDS)).isZero();
            assertThat(initial.get("query_attempt_count")).isEqualTo(1);
            assertThat(initial.get("query_lease_expires_at")).isNotNull();
            assertThat(newerAttempt.attemptToken()).isEqualTo(2);
            assertThat(staleGateway.queryPaymentNos).isEmpty();
            Map<String, Object> persisted = jdbcTemplate.queryForMap("""
                    SELECT status, operation_state, query_attempt_count, query_lease_expires_at
                    FROM payment_order WHERE id = 960440
                    """);
            assertThat(persisted)
                    .containsEntry("status", "PENDING")
                    .containsEntry("operation_state", "COMPLETED")
                    .containsEntry("query_attempt_count", 2);
            assertThat(toLocalDateTime(persisted.get("query_lease_expires_at")))
                    .isEqualTo(newerAttempt.leaseExpiresAt());
        } finally {
            transactions.releasePausedCommit();
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentQueryWorkersInvokeProviderExactlyOnceWhileLeaseIsValid() throws Exception {
        allowOrder(960_033L, 1L);
        paymentOrderRepository.saveAndFlush(queryReadyPayment(960_450L, 960_033L, "PAY960450", 1L));
        BlockingQueryGateway blockingGateway = new BlockingQueryGateway();
        PaymentApplicationService queryService = applicationService(blockingGateway);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Integer> firstWorker = executor.submit(queryService::queryTimedOutPayments);
            blockingGateway.awaitQuery();
            Future<Integer> secondWorker = executor.submit(queryService::queryTimedOutPayments);

            assertThat(secondWorker.get(10, TimeUnit.SECONDS)).isZero();
            assertThat(blockingGateway.queryCalls()).isEqualTo(1);
            blockingGateway.releaseQuery();
            assertThat(firstWorker.get(10, TimeUnit.SECONDS)).isZero();
        } finally {
            blockingGateway.releaseQuery();
            executor.shutdownNow();
        }

        assertThat(blockingGateway.queryCalls()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForMap("""
                        SELECT status, query_attempt_count, query_lease_expires_at
                        FROM payment_order WHERE id = 960450
                        """))
                .containsEntry("status", "PENDING")
                .containsEntry("query_attempt_count", 1)
                .containsEntry("query_lease_expires_at", null);
    }

    @Test
    void scheduledQueryRecoversTenantTwoOutsideProviderTransaction() {
        allowOrder(960_034L, 2L);
        paymentOrderRepository.saveAndFlush(queryReadyPayment(960_460L, 960_034L, "PAY960460", 2L));
        gateway.queryResult =
                new PaymentGatewayResult(PaymentStatus.PAID, "MYSQL-QUERY-PAY960460", null, new BigDecimal("100.00"));
        TenantContext.setTenantId(77L);

        service.queryTimedOutPaymentsScheduled();

        assertThat(TenantContext.currentTenantId()).contains(77L);
        assertThat(gateway.queryPaymentNos).containsExactly("PAY960460");
        assertThat(jdbcTemplate.queryForMap("""
                        SELECT tenant_id, status, query_attempt_count,
                               query_lease_expires_at, next_query_at
                        FROM payment_order WHERE id = 960460
                        """))
                .containsEntry("tenant_id", 2L)
                .containsEntry("status", "PAID")
                .containsEntry("query_attempt_count", 1)
                .containsEntry("query_lease_expires_at", null)
                .containsEntry("next_query_at", null);
    }

    @Test
    void staleQueryResultCannotOverwriteNewerAttempt() throws Exception {
        allowOrder(960_035L, 1L);
        paymentOrderRepository.saveAndFlush(queryReadyPayment(960_470L, 960_035L, "PAY960470", 1L));
        TwoWorkerQueryGateway twoWorkerGateway = new TwoWorkerQueryGateway();
        PaymentApplicationService queryService = applicationService(twoWorkerGateway);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Integer> staleWorker = executor.submit(queryService::queryTimedOutPayments);
            twoWorkerGateway.awaitFirstQuery();
            jdbcTemplate.update(
                    "UPDATE payment_order SET query_lease_expires_at = ? WHERE id = 960470",
                    LocalDateTime.now().minusMinutes(1));
            Future<Integer> newWorker = executor.submit(queryService::queryTimedOutPayments);
            twoWorkerGateway.awaitSecondQuery();

            twoWorkerGateway.releaseFirstQuery();
            assertThat(staleWorker.get(10, TimeUnit.SECONDS)).isZero();
            assertThat(jdbcTemplate.queryForMap("""
                            SELECT status, query_attempt_count, query_lease_expires_at
                            FROM payment_order WHERE id = 960470
                            """))
                    .containsEntry("status", "PENDING")
                    .containsEntry("query_attempt_count", 2)
                    .doesNotContainEntry("query_lease_expires_at", null);

            twoWorkerGateway.releaseSecondQuery();
            assertThat(newWorker.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        } finally {
            twoWorkerGateway.releaseFirstQuery();
            twoWorkerGateway.releaseSecondQuery();
            executor.shutdownNow();
        }

        assertThat(twoWorkerGateway.queryCalls()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForMap("""
                        SELECT status, query_attempt_count, query_lease_expires_at, next_query_at
                        FROM payment_order WHERE id = 960470
                        """))
                .containsEntry("status", "PAID")
                .containsEntry("query_attempt_count", 2)
                .containsEntry("query_lease_expires_at", null)
                .containsEntry("next_query_at", null);
    }

    @Test
    void scheduledRecoveryContinuesToTenantTwoAndHandlesPaymentRefundAndPendingAudit() {
        allowOrder(960_010L, 1L);
        allowOrder(960_011L, 2L);
        allowOrder(960_012L, 2L);
        allowOrder(960_013L, 2L);
        paymentOrderRepository.saveAndFlush(expiredPayment(960_400L, 960_010L, "PAY960400", "tenant-one-failure", 1L));
        paymentOrderRepository.saveAndFlush(expiredPayment(960_401L, 960_011L, "PAY960401", "tenant-two-payment", 2L));
        paymentOrderRepository.saveAndFlush(paidPayment(960_402L, 960_012L, "PAY960402", 2L, BigDecimal.ZERO));
        paymentOrderRepository.saveAndFlush(paidPayment(960_403L, 960_013L, "PAY960403", 2L, new BigDecimal("5.00")));
        paymentLedgerRepository.saveAndFlush(
                expiredRefund(960_500L, 960_402L, 960_012L, "tenant-two-refund", new BigDecimal("10.00"), 2L));
        paymentLedgerRepository.saveAndFlush(
                pendingRefundAudit(960_501L, 960_403L, 960_013L, "tenant-two-audit", new BigDecimal("5.00"), 2L));
        gateway.failedCreatePaymentNo = "PAY960400";
        service = applicationService(gateway, new AuditService(new JpaAuditLogStore(auditLogRepository), 180));
        TenantContext.setTenantId(77L);

        service.recoverExpiredOperationsScheduled();

        assertThat(TenantContext.currentTenantId()).contains(77L);
        assertThat(jdbcTemplate.queryForMap(
                        "SELECT operation_state, attempt_count FROM payment_order WHERE id = 960400"))
                .containsEntry("operation_state", "RETRYABLE")
                .containsEntry("attempt_count", 2);
        assertThat(jdbcTemplate.queryForMap(
                        "SELECT operation_state, attempt_count FROM payment_order WHERE id = 960401"))
                .containsEntry("operation_state", "COMPLETED")
                .containsEntry("attempt_count", 2);
        assertThat(
                        jdbcTemplate.queryForMap(
                                "SELECT operation_state, attempt_count, status, audit_state FROM payment_ledger WHERE id = 960500"))
                .containsEntry("operation_state", "COMPLETED")
                .containsEntry("attempt_count", 2)
                .containsEntry("status", "SUCCESS")
                .containsEntry("audit_state", "DELIVERED");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT refunded_amount FROM payment_order WHERE id = 960402", BigDecimal.class))
                .isEqualByComparingTo("10.00");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT audit_state FROM payment_ledger WHERE id = 960501", String.class))
                .isEqualTo("DELIVERED");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT refunded_amount FROM payment_order WHERE id = 960403", BigDecimal.class))
                .isEqualByComparingTo("5.00");
        assertThat(gateway.createTokens).contains("PAY960400", "PAY960401");
        assertThat(gateway.refundTokens).containsExactly("tenant-two-refund");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM audit_log WHERE event_type = 'PAYMENT_REFUNDED'", Long.class))
                .isEqualTo(2L);
    }

    @Test
    void terminalRefundFailureReleasesReservedAmount() {
        allowOrder(960_020L, 1L);
        paymentOrderRepository.saveAndFlush(paidPayment(960_410L, 960_020L, "PAY960410", 1L, BigDecimal.ZERO));
        gateway.refundFailure = PaymentGatewayException.rejected("REFUND_DECLINED", "raw decline");

        Throwable failure = catchThrowable(() -> service.refund(
                USER,
                new PaymentRefundRequestDto("PAY960410", new BigDecimal("30.00"), "declined"),
                "terminal-refund"));

        assertThat(failure).isInstanceOf(PaymentGatewayException.class);
        assertThat(jdbcTemplate.queryForMap("""
                        SELECT status, operation_state, terminal_failure_code, lease_expires_at
                        FROM payment_ledger
                        WHERE payment_id = 960410 AND request_key = 'terminal-refund'
                        """))
                .containsEntry("status", "FAILED")
                .containsEntry("operation_state", "TERMINAL_FAILED")
                .containsEntry("terminal_failure_code", "REFUND_DECLINED")
                .containsEntry("lease_expires_at", null);
        assertThat(jdbcTemplate.queryForObject("""
                        SELECT COALESCE(SUM(amount), 0)
                        FROM payment_ledger
                        WHERE payment_id = 960410 AND ledger_type = 'REFUND' AND status = 'ACCEPTED'
                        """, BigDecimal.class)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT refunded_amount FROM payment_order WHERE id = 960410", BigDecimal.class))
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(gateway.refundTokens).containsExactly("PAY960410:refund:960100");
    }

    @Test
    void auditInsertRollbackReplaysOnceWithoutDuplicatingRefundSideEffects() {
        allowOrder(960_021L, 1L);
        paymentOrderRepository.saveAndFlush(paidPayment(960_420L, 960_021L, "PAY960420", 1L, BigDecimal.ZERO));
        FailAfterInsertAuditService auditService = new FailAfterInsertAuditService(auditLogRepository);
        service = applicationService(gateway, auditService);
        PaymentRefundRequestDto request = new PaymentRefundRequestDto("PAY960420", new BigDecimal("30.00"), "accepted");

        service.refund(USER, request, "audit-replay");

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT refunded_amount FROM payment_order WHERE id = 960420", BigDecimal.class))
                .isEqualByComparingTo("30.00");
        assertThat(jdbcTemplate.queryForMap("""
                        SELECT status, operation_state, attempt_count, audit_state,
                               response_refunded_amount, response_payment_status, response_ledger_status
                        FROM payment_ledger
                        WHERE payment_id = 960420 AND request_key = 'audit-replay'
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
                        "SELECT audit_state FROM payment_ledger WHERE payment_id = 960420 AND request_key = 'audit-replay'",
                        String.class))
                .isEqualTo("DELIVERED");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT refunded_amount FROM payment_order WHERE id = 960420", BigDecimal.class))
                .isEqualByComparingTo("30.00");
        assertThat(jdbcTemplate.queryForObject("""
                        SELECT COUNT(*)
                        FROM payment_ledger
                        WHERE payment_id = 960420 AND request_key = 'audit-replay'
                          AND status = 'SUCCESS' AND amount = 30.00
                        """, Long.class)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM audit_log WHERE event_type = 'PAYMENT_REFUNDED'", Long.class))
                .isEqualTo(1L);
        assertThat(gateway.refundTokens).containsExactly("PAY960420:refund:960100");
        assertThat(auditService.deliveryAttempts()).isEqualTo(2);
    }

    @Test
    void v50FixtureBlocksDuplicatesThenBackfillsLegacyRowsThroughV51() throws Exception {
        restoreSchemaAfterTest = true;
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .cleanDisabled(false)
                .target(MigrationVersion.fromVersion("50"))
                .load();
        flyway.clean();
        flyway.migrate();
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/fixtures/payment_v50_task4_review.sql"));
        }

        Flyway latest =
                Flyway.configure().dataSource(dataSource).cleanDisabled(false).load();
        Throwable blocked = catchThrowable(latest::migrate);
        assertThat(blocked).isNotNull();
        assertThat(causeMessages(blocked)).contains("ck_v51_resolve_duplicate_active_payment_intents");

        assertThat(jdbcTemplate.update("DELETE FROM payment_order WHERE id = 905102"))
                .isEqualTo(1);
        latest.repair();
        latest.migrate();

        Map<String, Object> payment = jdbcTemplate.queryForMap("""
                SELECT operation_state, attempt_count, lease_expires_at, last_failure_classification,
                       request_fingerprint, response_paid_amount, response_refunded_amount,
                       response_status, response_provider_trade_no, response_paid_at
                FROM payment_order WHERE id = 905103
                """);
        assertThat(payment.get("operation_state")).isEqualTo("LEGACY_UNREPLAYABLE");
        assertThat(((Number) payment.get("attempt_count")).intValue()).isZero();
        assertThat(payment.get("lease_expires_at")).isNull();
        assertThat(payment.get("last_failure_classification")).isEqualTo("LEGACY_UNKNOWN");
        assertThat(payment.get("request_fingerprint").toString()).hasSize(64);
        assertThat(payment.get("response_paid_amount")).isNull();
        assertThat(payment.get("response_refunded_amount")).isNull();
        assertThat(payment.get("response_status")).isNull();
        assertThat(payment.get("response_provider_trade_no")).isNull();
        assertThat(payment.get("response_paid_at")).isNull();

        Map<String, Object> refund = jdbcTemplate.queryForMap("""
                SELECT operation_state, attempt_count, lease_expires_at, last_failure_classification,
                       request_fingerprint, response_refunded_amount, response_payment_status,
                       response_ledger_status, audit_state, audit_event_type, audit_actor_user_id,
                       audit_actor_role, audit_source_ip, audit_include_owner, audit_detail
                FROM payment_ledger WHERE id = 905104
                """);
        assertThat(refund.get("operation_state")).isEqualTo("LEGACY_UNREPLAYABLE");
        assertThat(((Number) refund.get("attempt_count")).intValue()).isZero();
        assertThat(refund.get("lease_expires_at")).isNull();
        assertThat(refund.get("last_failure_classification")).isEqualTo("LEGACY_UNKNOWN");
        assertThat(refund.get("request_fingerprint")).isNull();
        assertThat(refund.get("response_refunded_amount")).isNull();
        assertThat(refund.get("response_payment_status")).isNull();
        assertThat(refund.get("response_ledger_status")).isNull();
        assertThat(refund.get("audit_state")).isEqualTo("NONE");
        assertThat(refund.get("audit_event_type")).isNull();
        assertThat(refund.get("audit_actor_user_id")).isNull();
        assertThat(refund.get("audit_actor_role")).isNull();
        assertThat(refund.get("audit_source_ip")).isNull();
        assertThat(((Number) refund.get("audit_include_owner")).intValue()).isZero();
        assertThat(refund.get("audit_detail")).isNull();

        Map<String, Object> legacyPending = jdbcTemplate.queryForMap("""
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

        allowOrder(905_100L, 1L, 905_101L);
        allowOrder(905_105L, 1L, 905_105L);
        gateway.queryResult =
                new PaymentGatewayResult(PaymentStatus.PAID, "MYSQL-LEGACY-QUERY", null, new BigDecimal("100.00"));

        assertThat(service.queryTimedOutPayments()).isEqualTo(2);
        Throwable replay = catchThrowable(() -> service.createPayment(
                new SessionUser(905_105L, "USER"),
                new PaymentCreateRequestDto(905_105L, PaymentMethod.WECHAT, null, null),
                "task4-legacy-pending-key"));

        assertThat(gateway.queryPaymentNos)
                .containsExactlyInAnyOrder("TASK4-DUPLICATE-PAYMENT-1", "TASK4-LEGACY-PENDING");
        assertThat(gateway.createTokens).isEmpty();
        assertThat(replay)
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        assertThat(jdbcTemplate.queryForMap("""
                        SELECT status, operation_state, query_attempt_count, next_query_at
                        FROM payment_order WHERE id = 905105
                        """))
                .containsEntry("status", "PAID")
                .containsEntry("operation_state", "LEGACY_UNREPLAYABLE")
                .containsEntry("query_attempt_count", 1)
                .containsEntry("next_query_at", null);
    }

    private void assertStalePaymentWorkerCannotOverwriteNewerAttempt(
            RuntimeException staleFailure, long paymentId, long orderId, String paymentNo) throws Exception {
        allowOrder(orderId, 1L);
        paymentOrderRepository.saveAndFlush(expiredPayment(paymentId, orderId, paymentNo, "stale-" + paymentId, 1L));
        TwoWorkerGateway twoWorkerGateway = new TwoWorkerGateway(staleFailure);
        PaymentApplicationService recoveryService = applicationService(twoWorkerGateway);
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

    private static LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime;
        }
        return ((Timestamp) value).toLocalDateTime();
    }

    private static Throwable awaitWorkerFailure(Future<?> worker) throws Exception {
        try {
            worker.get(10, TimeUnit.SECONDS);
            return null;
        } catch (ExecutionException exception) {
            return exception.getCause();
        }
    }

    private PaymentApplicationService applicationService(PaymentGateway paymentGateway) {
        return applicationService(paymentGateway, mock(AuditService.class));
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
                InventoryReservationLifecycle.noop(),
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
                "testcontainers-secret");
    }

    private void allowOrder(Long orderId, Long tenantId) {
        allowOrder(orderId, tenantId, 42L);
    }

    private void allowOrder(Long orderId, Long tenantId, Long userId) {
        jdbcTemplate.update(
                "INSERT INTO orders (id, order_no, user_id, price, status, tenant_id) VALUES (?, ?, ?, ?, ?, ?)",
                orderId,
                "ORD" + orderId,
                userId,
                new BigDecimal("100.00"),
                "PAID",
                tenantId);
        visibleOrders.put(orderId, order(orderId, userId));
    }

    private void restoreSchemaAtV51() {
        Flyway latest =
                Flyway.configure().dataSource(dataSource).cleanDisabled(false).load();
        latest.clean();
        latest.migrate();
    }

    private PaymentResponseDto create(Long orderId, String key) {
        return service.createPayment(USER, new PaymentCreateRequestDto(orderId, PaymentMethod.WECHAT, null, null), key);
    }

    private int recoverAfter(CountDownLatch start) throws Exception {
        start.await(10, TimeUnit.SECONDS);
        return service.recoverExpiredOperations();
    }

    private void resetPayments() {
        jdbcTemplate.update("DELETE FROM payment_ledger");
        jdbcTemplate.update("DELETE FROM payment_order");
    }

    private static List<Object> invokeConcurrently(List<Callable<PaymentResponseDto>> calls) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(calls.size());
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<PaymentResponseDto> call : calls) {
                futures.add(executor.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    try {
                        return call.call();
                    } catch (RuntimeException exception) {
                        return exception;
                    }
                }));
            }
            start.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> future : futures) {
                outcomes.add(future.get(15, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            executor.shutdownNow();
        }
    }

    private static void assertOneSuccessAndOneConflict(List<Object> outcomes) {
        assertThat(outcomes.stream().filter(PaymentResponseDto.class::isInstance))
                .hasSize(1);
        assertThat(outcomes.stream().filter(BusinessException.class::isInstance).map(BusinessException.class::cast))
                .singleElement()
                .extracting(BusinessException::errorCode)
                .isEqualTo(ErrorCode.CONFLICT);
    }

    private static OrderRecord order(Long orderId) {
        return order(orderId, 42L);
    }

    private static OrderRecord order(Long orderId, Long userId) {
        return new OrderRecord(
                orderId,
                "ORD" + orderId,
                userId,
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

    private static PaymentOrderEntity expiredPayment(
            Long id, Long orderId, String paymentNo, String idempotencyKey, Long tenantId) {
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
        entity.setLeaseExpiresAt(now.minusMinutes(1));
        entity.setLastFailureClassification(PaymentFailureClassification.NONE);
        entity.setMerchantToken(paymentNo);
        entity.setCreateTime(now.minusMinutes(5));
        entity.setUpdateTime(now.minusMinutes(5));
        return entity;
    }

    private static PaymentOrderEntity queryReadyPayment(Long id, Long orderId, String paymentNo, Long tenantId) {
        PaymentOrderEntity entity = expiredPayment(id, orderId, paymentNo, "query-" + id, tenantId);
        entity.setOperationState(PaymentOperationState.COMPLETED);
        entity.setLeaseExpiresAt(null);
        entity.setProviderTradeNo("MYSQL-PREPAY-" + paymentNo);
        entity.setResponsePaidAmount(BigDecimal.ZERO.setScale(2));
        entity.setResponseRefundedAmount(BigDecimal.ZERO.setScale(2));
        entity.setResponseStatus(PaymentStatus.PENDING);
        entity.setResponseProviderTradeNo("MYSQL-PREPAY-" + paymentNo);
        entity.setQueryAttemptCount(0);
        entity.setQueryLeaseExpiresAt(null);
        entity.setNextQueryAt(LocalDateTime.now().minusMinutes(1));
        return entity;
    }

    private static PaymentOrderEntity paidPayment(
            Long id, Long orderId, String paymentNo, Long tenantId, BigDecimal refundedAmount) {
        PaymentOrderEntity entity = expiredPayment(id, orderId, paymentNo, "paid-" + id, tenantId);
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
        private Runnable createObserver = () -> {};
        private String failedCreatePaymentNo;
        private RuntimeException refundFailure;
        private PaymentGatewayResult queryResult;
        private final AtomicInteger coordinatedCreates = new AtomicInteger();
        private CountDownLatch firstCreateEntered;
        private CountDownLatch releaseFirstCreate;

        @Override
        public PaymentGatewayResult create(PaymentOrder payment, String merchantToken) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isFalse();
            createTokens.add(merchantToken);
            createObserver.run();
            if (payment.paymentNo().equals(failedCreatePaymentNo)) {
                throw new IllegalStateException("tenant one gateway failed");
            }
            if (firstCreateEntered != null && coordinatedCreates.incrementAndGet() == 1) {
                firstCreateEntered.countDown();
                await(releaseFirstCreate, "release first MySQL create");
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

        private void blockFirstCreate() {
            firstCreateEntered = new CountDownLatch(1);
            releaseFirstCreate = new CountDownLatch(1);
        }

        private void awaitFirstCreate() {
            await(firstCreateEntered, "first MySQL create");
        }

        private void releaseFirstCreate() {
            if (releaseFirstCreate != null) {
                releaseFirstCreate.countDown();
            }
        }

        private static void await(CountDownLatch latch, String description) {
            try {
                if (latch == null || !latch.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out waiting for " + description);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted waiting for " + description, exception);
            }
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
                RecordingGateway.await(releaseFirstCreate, "release stale MySQL create");
                if (firstFailure != null) {
                    throw firstFailure;
                }
            } else if (call == 2) {
                secondCreateEntered.countDown();
                RecordingGateway.await(releaseSecondCreate, "release new MySQL create");
            }
            return new PaymentGatewayResult(
                    PaymentStatus.PENDING,
                    "MYSQL-" + merchantToken,
                    "/mysql/payments/" + payment.paymentNo(),
                    payment.amount());
        }

        @Override
        public PaymentGatewayResult query(PaymentOrder payment) {
            throw new AssertionError("query is not expected");
        }

        @Override
        public PaymentGatewayResult refund(PaymentOrder payment, BigDecimal amount, String merchantToken) {
            throw new AssertionError("refund is not expected");
        }

        private void awaitFirstCreate() {
            RecordingGateway.await(firstCreateEntered, "stale MySQL create");
        }

        private void awaitSecondCreate() {
            RecordingGateway.await(secondCreateEntered, "new MySQL create");
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
            RecordingGateway.await(releaseQuery, "release MySQL query");
            return new PaymentGatewayResult(PaymentStatus.PENDING, null, null, payment.amount());
        }

        @Override
        public PaymentGatewayResult refund(PaymentOrder payment, BigDecimal amount, String merchantToken) {
            throw new AssertionError("refund is not expected");
        }

        private void awaitQuery() {
            RecordingGateway.await(queryEntered, "MySQL query");
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
                RecordingGateway.await(releaseFirstQuery, "release stale MySQL query");
                return new PaymentGatewayResult(PaymentStatus.FAILED, null, null, BigDecimal.ZERO);
            }
            if (call == 2) {
                secondQueryEntered.countDown();
                RecordingGateway.await(releaseSecondQuery, "release new MySQL query");
                return new PaymentGatewayResult(PaymentStatus.PAID, "MYSQL-QUERY-PAID", null, payment.amount());
            }
            throw new AssertionError("unexpected query invocation " + call);
        }

        @Override
        public PaymentGatewayResult refund(PaymentOrder payment, BigDecimal amount, String merchantToken) {
            throw new AssertionError("refund is not expected");
        }

        private void awaitFirstQuery() {
            RecordingGateway.await(firstQueryEntered, "stale MySQL query");
        }

        private void awaitSecondQuery() {
            RecordingGateway.await(secondQueryEntered, "new MySQL query");
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
            RecordingGateway.await(pausedCommit, "committed MySQL transaction pause");
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
                RecordingGateway.await(releaseCommit, "release committed MySQL transaction");
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
}
