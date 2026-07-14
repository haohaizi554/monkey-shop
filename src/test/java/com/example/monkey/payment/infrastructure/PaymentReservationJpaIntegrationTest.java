package com.example.monkey.payment.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.example.monkey.order.domain.OrderStore;
import com.example.monkey.payment.application.PaymentApplicationService;
import com.example.monkey.payment.application.dto.PaymentRefundRequestDto;
import com.example.monkey.payment.domain.PaymentCallbackReplayGuard;
import com.example.monkey.payment.domain.PaymentFailureClassification;
import com.example.monkey.payment.domain.PaymentGateway;
import com.example.monkey.payment.domain.PaymentGatewayException;
import com.example.monkey.payment.domain.PaymentGatewayResult;
import com.example.monkey.payment.domain.PaymentLedgerStatus;
import com.example.monkey.payment.domain.PaymentLedgerType;
import com.example.monkey.payment.domain.PaymentMethod;
import com.example.monkey.payment.domain.PaymentOperationState;
import com.example.monkey.payment.domain.PaymentRecoveryTenantSource;
import com.example.monkey.payment.domain.PaymentStatus;
import com.example.monkey.payment.domain.PaymentTransitionPolicy;
import com.example.monkey.payment.domain.PaymentTransitionResolver;
import com.example.monkey.payment.domain.RefundAuditState;
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
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest(showSql = false)
@ActiveProfiles("test")
@MockitoBean(types = PiiCryptoService.class)
@Import(RequiresNewPaymentTransactions.class)
class PaymentReservationJpaIntegrationTest {

    private static final SessionUser USER = new SessionUser(42L, "USER");

    private final TestEntityManager entityManager;
    private final PaymentOrderRepository paymentOrderRepository;
    private final PaymentLedgerRepository paymentLedgerRepository;
    private final PaymentReconciliationReportRepository reconciliationReportRepository;
    private final PiiCryptoService piiCryptoService;
    private final AuditLogRepository auditLogRepository;
    private final RequiresNewPaymentTransactions paymentTransactions;
    private final PlatformTransactionManager transactionManager;
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    PaymentReservationJpaIntegrationTest(
            TestEntityManager entityManager,
            PaymentOrderRepository paymentOrderRepository,
            PaymentLedgerRepository paymentLedgerRepository,
            PaymentReconciliationReportRepository reconciliationReportRepository,
            PiiCryptoService piiCryptoService,
            AuditLogRepository auditLogRepository,
            RequiresNewPaymentTransactions paymentTransactions,
            PlatformTransactionManager transactionManager,
            DataSource dataSource) {
        this.entityManager = entityManager;
        this.paymentOrderRepository = paymentOrderRepository;
        this.paymentLedgerRepository = paymentLedgerRepository;
        this.reconciliationReportRepository = reconciliationReportRepository;
        this.piiCryptoService = piiCryptoService;
        this.auditLogRepository = auditLogRepository;
        this.paymentTransactions = paymentTransactions;
        this.transactionManager = transactionManager;
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @Test
    void databaseRejectsSameTenantUserAndIdempotencyKey() {
        paymentOrderRepository.saveAndFlush(payment(100L, 1L, 10L, "PAY100", "same-key"));

        assertThatThrownBy(() -> paymentOrderRepository.saveAndFlush(payment(101L, 1L, 11L, "PAY101", "same-key")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sameUserAndIdempotencyKeyRemainIndependentAcrossTenants() {
        paymentOrderRepository.saveAndFlush(payment(100L, 1L, 10L, "PAY100", "same-key"));
        paymentOrderRepository.saveAndFlush(payment(101L, 2L, 11L, "PAY101", "same-key"));
        entityManager.clear();

        Number rows = (Number) entityManager
                .getEntityManager()
                .createNativeQuery("select count(*) from payment_order where id in (100, 101)")
                .getSingleResult();

        assertThat(rows.longValue()).isEqualTo(2L);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void paymentReservationSurvivesOuterTransactionRollback() {
        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        outer.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);

        assertThatThrownBy(() -> outer.executeWithoutResult(status -> {
                    paymentTransactions.execute(() -> {
                        paymentOrderRepository.saveAndFlush(payment(200L, 1L, 20L, "PAY200", "outer-rollback"));
                        return null;
                    });
                    throw new IllegalStateException("roll back caller");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(paymentOrderRepository.existsById(200L)).isTrue();
        paymentOrderRepository.deleteById(200L);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentRecoveryClaimsAnExpiredPaymentOnlyOnce() throws Exception {
        PaymentOrderEntity expired = payment(300L, 1L, 30L, "PAY300", "recover-once");
        expired.setLeaseExpiresAt(LocalDateTime.now().minusMinutes(1));
        paymentOrderRepository.saveAndFlush(expired);
        BlockingGateway gateway = new BlockingGateway();
        PaymentApplicationService service = paymentService(gateway, mock(AuditService.class));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        ExecutorCompletionService<Integer> recoveries = new ExecutorCompletionService<>(executor);
        CountDownLatch start = new CountDownLatch(1);

        try {
            recoveries.submit(() -> recoverAfter(start, service));
            recoveries.submit(() -> recoverAfter(start, service));
            start.countDown();
            gateway.awaitCreate();

            Future<Integer> nonWinner = recoveries.poll(5, TimeUnit.SECONDS);
            assertThat(nonWinner).isNotNull();
            assertThat(nonWinner.get(5, TimeUnit.SECONDS)).isZero();

            gateway.releaseCreate();
            assertThat(recoveries.poll(5, TimeUnit.SECONDS).get(5, TimeUnit.SECONDS))
                    .isEqualTo(1);
        } finally {
            gateway.releaseCreate();
            executor.shutdownNow();
        }

        PaymentOrderEntity completed = paymentOrderRepository.findById(300L).orElseThrow();
        assertThat(gateway.createCalls()).isEqualTo(1);
        assertThat(completed.getOperationState()).isEqualTo(PaymentOperationState.COMPLETED);
        assertThat(completed.getAttemptCount()).isEqualTo(2);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void stalePaymentCompletionCannotOverwriteANewerAttempt() throws Exception {
        assertStalePaymentWorkerCannotOverwriteNewerAttempt(null, 310L, "PAY310");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void stalePaymentTerminalFailureCannotOverwriteANewerAttempt() throws Exception {
        assertStalePaymentWorkerCannotOverwriteNewerAttempt(
                PaymentGatewayException.rejected("CARD_DECLINED", "raw decline"), 311L, "PAY311");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void refundFailureReleasesReservationAndAuditFailureReplaysExactlyOnce() {
        auditLogRepository.deleteAll();
        paymentOrderRepository.saveAndFlush(paidPayment(320L, "PAY320"));
        ConfigurableRefundGateway gateway = new ConfigurableRefundGateway();
        FailOnceAuditService auditService = new FailOnceAuditService(new JpaAuditLogStore(auditLogRepository));
        PaymentApplicationService service = paymentService(gateway, auditService);
        gateway.refundFailure = PaymentGatewayException.rejected("REFUND_DECLINED", "raw refund decline");

        assertThatThrownBy(() -> service.refund(
                        USER,
                        new PaymentRefundRequestDto("PAY320", new BigDecimal("30.00"), "declined"),
                        "failed-refund"))
                .isInstanceOf(PaymentGatewayException.class);

        PaymentLedgerEntity failed = paymentLedgerRepository
                .findByPaymentIdAndLedgerTypeAndRequestKey(320L, PaymentLedgerType.REFUND, "failed-refund")
                .orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(PaymentLedgerStatus.FAILED);
        assertThat(failed.getOperationState()).isEqualTo(PaymentOperationState.TERMINAL_FAILED);
        assertThat(paymentLedgerRepository.sumAmountByPaymentIdAndTypeAndStatus(
                        320L, PaymentLedgerType.REFUND, PaymentLedgerStatus.ACCEPTED))
                .isEqualByComparingTo(BigDecimal.ZERO);

        gateway.refundFailure = null;
        PaymentRefundRequestDto accepted = new PaymentRefundRequestDto("PAY320", new BigDecimal("30.00"), "accepted");
        service.refund(USER, accepted, "successful-refund");

        PaymentOrderEntity afterCompletion =
                paymentOrderRepository.findById(320L).orElseThrow();
        PaymentLedgerEntity pendingAudit = paymentLedgerRepository
                .findByPaymentIdAndLedgerTypeAndRequestKey(320L, PaymentLedgerType.REFUND, "successful-refund")
                .orElseThrow();
        assertThat(afterCompletion.getRefundedAmount()).isEqualByComparingTo("30.00");
        assertThat(pendingAudit.getStatus()).isEqualTo(PaymentLedgerStatus.SUCCESS);
        assertThat(pendingAudit.getOperationState()).isEqualTo(PaymentOperationState.COMPLETED);
        assertThat(pendingAudit.getAuditState()).isEqualTo(RefundAuditState.PENDING);
        assertThat(auditLogRepository.count()).isZero();

        service.refund(USER, accepted, "successful-refund");
        service.refund(USER, accepted, "successful-refund");

        PaymentOrderEntity afterReplays = paymentOrderRepository.findById(320L).orElseThrow();
        PaymentLedgerEntity delivered = paymentLedgerRepository
                .findByPaymentIdAndLedgerTypeAndRequestKey(320L, PaymentLedgerType.REFUND, "successful-refund")
                .orElseThrow();
        assertThat(afterReplays.getRefundedAmount()).isEqualByComparingTo("30.00");
        assertThat(delivered.getAuditState()).isEqualTo(RefundAuditState.DELIVERED);
        assertThat(gateway.refundCalls()).isEqualTo(2);
        assertThat(auditService.deliveryAttempts()).isEqualTo(2);
        assertThat(auditLogRepository.count()).isEqualTo(1L);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void schedulerDiscoversTenantTwoAndContinuesAfterTenantOneGatewayFailure() {
        PaymentOrderEntity tenantOne = payment(330L, 1L, 330L, "PAY330", "tenant-one-recovery");
        tenantOne.setLeaseExpiresAt(LocalDateTime.now().minusMinutes(1));
        paymentOrderRepository.saveAndFlush(tenantOne);
        PaymentOrderEntity tenantTwo = payment(331L, 2L, 331L, "PAY331", "tenant-two-recovery");
        tenantTwo.setLeaseExpiresAt(LocalDateTime.now().minusMinutes(1));
        paymentOrderRepository.saveAndFlush(tenantTwo);
        TenantAwareGateway gateway = new TenantAwareGateway("PAY330");
        PaymentApplicationService service =
                paymentService(gateway, mock(AuditService.class), new JdbcPaymentRecoveryTenantSource(jdbcTemplate));
        TenantContext.setTenantId(77L);

        try {
            service.recoverExpiredOperationsScheduled();

            assertThat(TenantContext.currentTenantId()).contains(77L);
            TenantContext.setTenantId(1L);
            PaymentOrderEntity failed = paymentOrderRepository.findById(330L).orElseThrow();
            assertThat(failed.getOperationState()).isEqualTo(PaymentOperationState.RETRYABLE);
            assertThat(failed.getAttemptCount()).isEqualTo(2);
            TenantContext.setTenantId(2L);
            PaymentOrderEntity completed = paymentOrderRepository.findById(331L).orElseThrow();
            assertThat(completed.getOperationState()).isEqualTo(PaymentOperationState.COMPLETED);
            assertThat(completed.getAttemptCount()).isEqualTo(2);
            assertThat(gateway.createTokens()).containsExactly("PAY330", "PAY331");
        } finally {
            TenantContext.clear();
        }
    }

    private void assertStalePaymentWorkerCannotOverwriteNewerAttempt(
            RuntimeException staleFailure, long paymentId, String paymentNo) throws Exception {
        PaymentOrderEntity expired = payment(paymentId, 1L, paymentId, paymentNo, "stale-" + paymentId);
        expired.setLeaseExpiresAt(LocalDateTime.now().minusMinutes(1));
        paymentOrderRepository.saveAndFlush(expired);
        TwoWorkerGateway gateway = new TwoWorkerGateway(staleFailure);
        PaymentApplicationService service = paymentService(gateway, mock(AuditService.class));
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Integer> staleWorker = executor.submit(service::recoverExpiredOperations);
            gateway.awaitFirstCreate();
            jdbcTemplate.update(
                    "UPDATE payment_order SET lease_expires_at = ? WHERE id = ?",
                    LocalDateTime.now().minusMinutes(1),
                    paymentId);
            Future<Integer> newWorker = executor.submit(service::recoverExpiredOperations);
            gateway.awaitSecondCreate();

            gateway.releaseFirstCreate();
            assertThat(staleWorker.get(5, TimeUnit.SECONDS)).isEqualTo(1);
            PaymentOrderEntity afterStaleWrite =
                    paymentOrderRepository.findById(paymentId).orElseThrow();
            assertThat(afterStaleWrite.getStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(afterStaleWrite.getOperationState()).isEqualTo(PaymentOperationState.RESERVED);
            assertThat(afterStaleWrite.getAttemptCount()).isEqualTo(3);
            assertThat(afterStaleWrite.getLastFailureClassification()).isEqualTo(PaymentFailureClassification.NONE);

            gateway.releaseSecondCreate();
            assertThat(newWorker.get(5, TimeUnit.SECONDS)).isEqualTo(1);
        } finally {
            gateway.releaseFirstCreate();
            gateway.releaseSecondCreate();
            executor.shutdownNow();
        }

        PaymentOrderEntity completed =
                paymentOrderRepository.findById(paymentId).orElseThrow();
        assertThat(gateway.createCalls()).isEqualTo(2);
        assertThat(completed.getOperationState()).isEqualTo(PaymentOperationState.COMPLETED);
        assertThat(completed.getAttemptCount()).isEqualTo(3);
    }

    private PaymentApplicationService paymentService(PaymentGateway gateway, AuditService auditService) {
        return paymentService(gateway, auditService, PaymentRecoveryTenantSource.none());
    }

    private PaymentApplicationService paymentService(
            PaymentGateway gateway, AuditService auditService, PaymentRecoveryTenantSource recoveryTenantSource) {
        JpaPaymentStore store = new JpaPaymentStore(
                paymentOrderRepository, paymentLedgerRepository, reconciliationReportRepository, piiCryptoService);
        PaymentCallbackReplayGuard replayGuard = (provider, paymentNo, callbackId, ttl) -> true;
        PaymentTransitionResolver resolver = (status, event) -> PaymentTransitionPolicy.nextStatus(status, event)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT, "invalid transition"));
        AtomicLong ids = new AtomicLong(10_000L);
        IdGenerator idGenerator = ids::getAndIncrement;
        return new PaymentApplicationService(
                store,
                gateway,
                replayGuard,
                resolver,
                mock(OrderStore.class),
                mock(UserAccountStore.class),
                mock(UserMfaVerifier.class),
                idGenerator,
                auditService,
                paymentTransactions,
                recoveryTenantSource,
                Duration.ofHours(24),
                Duration.ofMinutes(5),
                Duration.ofMinutes(2),
                Duration.ofSeconds(30),
                new BigDecimal("5000.00"),
                "jpa-integration-secret");
    }

    private static int recoverAfter(CountDownLatch start, PaymentApplicationService service) throws Exception {
        start.await(5, TimeUnit.SECONDS);
        return service.recoverExpiredOperations();
    }

    private static final class BlockingGateway implements PaymentGateway {

        private final AtomicInteger createCalls = new AtomicInteger();
        private final CountDownLatch createEntered = new CountDownLatch(1);
        private final CountDownLatch releaseCreate = new CountDownLatch(1);

        @Override
        public PaymentGatewayResult create(
                com.example.monkey.payment.domain.PaymentOrder payment, String merchantToken) {
            createCalls.incrementAndGet();
            createEntered.countDown();
            await(releaseCreate, "release payment create");
            return new PaymentGatewayResult(
                    PaymentStatus.PENDING,
                    "JPA-" + merchantToken,
                    "/jpa/payments/" + payment.paymentNo(),
                    payment.amount());
        }

        @Override
        public PaymentGatewayResult query(com.example.monkey.payment.domain.PaymentOrder payment) {
            return new PaymentGatewayResult(PaymentStatus.PENDING, null, null, payment.amount());
        }

        @Override
        public PaymentGatewayResult refund(
                com.example.monkey.payment.domain.PaymentOrder payment, BigDecimal amount, String merchantToken) {
            return new PaymentGatewayResult(PaymentStatus.PARTIALLY_REFUNDED, "JPA-RF-" + merchantToken, null, amount);
        }

        private void awaitCreate() {
            await(createEntered, "payment create");
        }

        private void releaseCreate() {
            releaseCreate.countDown();
        }

        private int createCalls() {
            return createCalls.get();
        }

        private static void await(CountDownLatch latch, String description) {
            try {
                if (!latch.await(5, TimeUnit.SECONDS)) {
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
        public PaymentGatewayResult create(
                com.example.monkey.payment.domain.PaymentOrder payment, String merchantToken) {
            int call = createCalls.incrementAndGet();
            if (call == 1) {
                firstCreateEntered.countDown();
                BlockingGateway.await(releaseFirstCreate, "release stale payment create");
                if (firstFailure != null) {
                    throw firstFailure;
                }
            } else if (call == 2) {
                secondCreateEntered.countDown();
                BlockingGateway.await(releaseSecondCreate, "release new payment create");
            }
            return new PaymentGatewayResult(
                    PaymentStatus.PENDING,
                    "JPA-" + merchantToken,
                    "/jpa/payments/" + payment.paymentNo(),
                    payment.amount());
        }

        @Override
        public PaymentGatewayResult query(com.example.monkey.payment.domain.PaymentOrder payment) {
            return new PaymentGatewayResult(PaymentStatus.PENDING, null, null, payment.amount());
        }

        @Override
        public PaymentGatewayResult refund(
                com.example.monkey.payment.domain.PaymentOrder payment, BigDecimal amount, String merchantToken) {
            return new PaymentGatewayResult(PaymentStatus.PARTIALLY_REFUNDED, "JPA-RF-" + merchantToken, null, amount);
        }

        private void awaitFirstCreate() {
            BlockingGateway.await(firstCreateEntered, "stale payment create");
        }

        private void awaitSecondCreate() {
            BlockingGateway.await(secondCreateEntered, "new payment create");
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

    private static final class ConfigurableRefundGateway implements PaymentGateway {

        private final AtomicInteger refundCalls = new AtomicInteger();
        private RuntimeException refundFailure;

        @Override
        public PaymentGatewayResult create(
                com.example.monkey.payment.domain.PaymentOrder payment, String merchantToken) {
            return new PaymentGatewayResult(
                    PaymentStatus.PENDING,
                    "JPA-" + merchantToken,
                    "/jpa/payments/" + payment.paymentNo(),
                    payment.amount());
        }

        @Override
        public PaymentGatewayResult query(com.example.monkey.payment.domain.PaymentOrder payment) {
            return new PaymentGatewayResult(PaymentStatus.PENDING, null, null, payment.amount());
        }

        @Override
        public PaymentGatewayResult refund(
                com.example.monkey.payment.domain.PaymentOrder payment, BigDecimal amount, String merchantToken) {
            refundCalls.incrementAndGet();
            if (refundFailure != null) {
                throw refundFailure;
            }
            return new PaymentGatewayResult(PaymentStatus.PARTIALLY_REFUNDED, "JPA-RF-" + merchantToken, null, amount);
        }

        private int refundCalls() {
            return refundCalls.get();
        }
    }

    private static final class TenantAwareGateway implements PaymentGateway {

        private final String failedPaymentNo;
        private final List<String> createTokens = new java.util.concurrent.CopyOnWriteArrayList<>();

        private TenantAwareGateway(String failedPaymentNo) {
            this.failedPaymentNo = failedPaymentNo;
        }

        @Override
        public PaymentGatewayResult create(
                com.example.monkey.payment.domain.PaymentOrder payment, String merchantToken) {
            createTokens.add(merchantToken);
            if (payment.paymentNo().equals(failedPaymentNo)) {
                throw new IllegalStateException("tenant one gateway failed");
            }
            return new PaymentGatewayResult(
                    PaymentStatus.PENDING,
                    "JPA-" + merchantToken,
                    "/jpa/payments/" + payment.paymentNo(),
                    payment.amount());
        }

        @Override
        public PaymentGatewayResult query(com.example.monkey.payment.domain.PaymentOrder payment) {
            return new PaymentGatewayResult(PaymentStatus.PENDING, null, null, payment.amount());
        }

        @Override
        public PaymentGatewayResult refund(
                com.example.monkey.payment.domain.PaymentOrder payment, BigDecimal amount, String merchantToken) {
            return new PaymentGatewayResult(PaymentStatus.PARTIALLY_REFUNDED, "JPA-RF-" + merchantToken, null, amount);
        }

        private List<String> createTokens() {
            return List.copyOf(createTokens);
        }
    }

    private static final class FailOnceAuditService extends AuditService {

        private final AtomicInteger deliveryAttempts = new AtomicInteger();

        private FailOnceAuditService(JpaAuditLogStore auditLogStore) {
            super(auditLogStore, 180);
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
            if (deliveryAttempts.incrementAndGet() == 1) {
                throw new IllegalStateException("audit delivery failed");
            }
            super.recordReliable(eventType, outcome, actorUserId, actorRole, subject, sourceIp, detail);
        }

        private int deliveryAttempts() {
            return deliveryAttempts.get();
        }
    }

    private static PaymentOrderEntity payment(
            Long id, Long tenantId, Long orderId, String paymentNo, String idempotencyKey) {
        LocalDateTime now = LocalDateTime.parse("2026-07-04T08:30:00");
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
        entity.setLeaseExpiresAt(now.plusMinutes(2));
        entity.setLastFailureClassification(PaymentFailureClassification.NONE);
        entity.setMerchantToken(paymentNo);
        entity.setCreateTime(now);
        entity.setUpdateTime(now);
        return entity;
    }

    private static PaymentOrderEntity paidPayment(Long id, String paymentNo) {
        PaymentOrderEntity entity = payment(id, 1L, id, paymentNo, "paid-" + id);
        LocalDateTime paidAt = LocalDateTime.now().minusMinutes(10);
        entity.setPaidAmount(new BigDecimal("100.00"));
        entity.setStatus(PaymentStatus.PAID);
        entity.setOperationState(PaymentOperationState.COMPLETED);
        entity.setLeaseExpiresAt(null);
        entity.setProviderTradeNo("JPA-PAID-" + paymentNo);
        entity.setPaidAt(paidAt);
        entity.setResponsePaidAmount(new BigDecimal("100.00"));
        entity.setResponseRefundedAmount(BigDecimal.ZERO.setScale(2));
        entity.setResponseStatus(PaymentStatus.PAID);
        entity.setResponseProviderTradeNo("JPA-PAID-" + paymentNo);
        entity.setResponsePaidAt(paidAt);
        return entity;
    }
}
