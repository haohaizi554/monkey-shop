package com.example.monkey.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.monkey.order.domain.OrderStore;
import com.example.monkey.order.domain.OrderStore.OrderRecord;
import com.example.monkey.payment.application.dto.PaymentCreateRequestDto;
import com.example.monkey.payment.application.dto.PaymentResponseDto;
import com.example.monkey.payment.domain.PaymentCallbackReplayGuard;
import com.example.monkey.payment.domain.PaymentGateway;
import com.example.monkey.payment.domain.PaymentGatewayResult;
import com.example.monkey.payment.domain.PaymentMethod;
import com.example.monkey.payment.domain.PaymentOrder;
import com.example.monkey.payment.domain.PaymentStatus;
import com.example.monkey.payment.domain.PaymentTransitionPolicy;
import com.example.monkey.payment.domain.PaymentTransitionResolver;
import com.example.monkey.payment.infrastructure.JpaPaymentStore;
import com.example.monkey.payment.infrastructure.PaymentLedgerRepository;
import com.example.monkey.payment.infrastructure.PaymentOrderRepository;
import com.example.monkey.payment.infrastructure.PaymentReconciliationReportRepository;
import com.example.monkey.payment.infrastructure.RequiresNewPaymentTransactions;
import com.example.monkey.shared.application.observability.AuditService;
import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.application.tenant.TenantContext;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.shared.domain.id.IdGenerator;
import com.example.monkey.shared.infrastructure.privacy.PiiCryptoService;
import com.example.monkey.user.domain.UserAccountStore;
import com.example.monkey.user.domain.UserMfaVerifier;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import org.springframework.jdbc.datasource.DriverManagerDataSource;
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
    private final PlatformTransactionManager transactionManager;
    private final JdbcTemplate jdbcTemplate;
    private final Map<Long, OrderRecord> visibleOrders = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong(960_100L);
    private RecordingGateway gateway;
    private PaymentApplicationService service;

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    PaymentMySqlContainerIntegrationTest(
            PaymentOrderRepository paymentOrderRepository,
            PaymentLedgerRepository paymentLedgerRepository,
            PaymentReconciliationReportRepository reconciliationReportRepository,
            PiiCryptoService piiCryptoService,
            PlatformTransactionManager transactionManager,
            DataSource dataSource) {
        this.paymentOrderRepository = paymentOrderRepository;
        this.paymentLedgerRepository = paymentLedgerRepository;
        this.reconciliationReportRepository = reconciliationReportRepository;
        this.piiCryptoService = piiCryptoService;
        this.transactionManager = transactionManager;
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(1L);
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
        TenantContext.clear();
    }

    @Test
    void sameKeySameFingerprintConvergesOnOneRowAndStableGatewayToken() throws Exception {
        allowOrder(960_001L, 1L);

        List<Object> outcomes =
                invokeConcurrently(List.of(() -> create(960_001L, "same-key"), () -> create(960_001L, "same-key")));

        assertThat(outcomes).allMatch(PaymentResponseDto.class::isInstance);
        assertThat(outcomes.get(1)).isEqualTo(outcomes.get(0));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payment_order", Long.class))
                .isEqualTo(1L);
        assertThat(gateway.createTokens).isNotEmpty().allMatch("PAY960100"::equals);
        assertThat(gateway.createTokens).hasSizeBetween(1, 2);
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
    void v50FixtureBlocksDuplicatesThenBackfillsLegacyRowsThroughV51() throws Exception {
        String schema = "task4_v50_fixture";
        String fixtureUser = "root";
        String fixturePassword = MYSQL.getPassword();
        JdbcTemplate rootJdbc =
                new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(), fixtureUser, fixturePassword));
        rootJdbc.execute("DROP DATABASE IF EXISTS " + schema);
        rootJdbc.execute("CREATE DATABASE " + schema);
        String fixtureUrl = MYSQL.getJdbcUrl().replace(MYSQL.getDatabaseName(), schema);
        Flyway flyway = Flyway.configure()
                .dataSource(fixtureUrl, fixtureUser, fixturePassword)
                .target(MigrationVersion.fromVersion("50"))
                .load();
        flyway.migrate();
        try (Connection connection = DriverManager.getConnection(fixtureUrl, fixtureUser, fixturePassword)) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/fixtures/payment_v50_task4_review.sql"));
        }

        Flyway latest = Flyway.configure()
                .dataSource(fixtureUrl, fixtureUser, fixturePassword)
                .load();
        Throwable blocked = catchThrowable(latest::migrate);
        assertThat(causeMessages(blocked)).contains("ck_v51_resolve_duplicate_active_payment_intents");

        JdbcTemplate fixtureJdbc =
                new JdbcTemplate(new DriverManagerDataSource(fixtureUrl, fixtureUser, fixturePassword));
        fixtureJdbc.update("DELETE FROM payment_order WHERE id = 905102");
        latest.repair();
        latest.migrate();

        Map<String, Object> payment = fixtureJdbc.queryForMap("""
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

        Map<String, Object> refund = fixtureJdbc.queryForMap("""
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
    }

    private PaymentApplicationService applicationService(RecordingGateway paymentGateway) {
        OrderStore orderStore = mock(OrderStore.class);
        when(orderStore.findVisibleByIdAndUserId(anyLong(), anyLong())).thenAnswer(invocation -> {
            OrderRecord order = visibleOrders.get(invocation.getArgument(0, Long.class));
            Long userId = invocation.getArgument(1, Long.class);
            return order != null && order.userId().equals(userId) ? Optional.of(order) : Optional.empty();
        });
        JpaPaymentStore store = new JpaPaymentStore(
                paymentOrderRepository, paymentLedgerRepository, reconciliationReportRepository, piiCryptoService);
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
                mock(AuditService.class),
                new RequiresNewPaymentTransactions(transactionManager),
                Clock.systemUTC(),
                Duration.ofHours(24),
                Duration.ofMinutes(5),
                new BigDecimal("5000.00"),
                "testcontainers-secret");
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

    private PaymentResponseDto create(Long orderId, String key) {
        return service.createPayment(USER, new PaymentCreateRequestDto(orderId, PaymentMethod.WECHAT, null, null), key);
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

    private static String causeMessages(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            messages.append(cause.getMessage()).append('\n');
        }
        return messages.toString();
    }

    private static final class RecordingGateway implements PaymentGateway {
        private final List<String> createTokens = new CopyOnWriteArrayList<>();
        private Runnable createObserver = () -> {};

        @Override
        public PaymentGatewayResult create(PaymentOrder payment, String merchantToken) {
            createTokens.add(merchantToken);
            createObserver.run();
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
    }
}
