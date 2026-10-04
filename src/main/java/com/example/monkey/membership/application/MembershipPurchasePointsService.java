package com.example.monkey.membership.application;

import com.example.monkey.membership.domain.MemberProfile;
import com.example.monkey.membership.domain.MembershipLevel;
import com.example.monkey.membership.domain.MembershipStore;
import com.example.monkey.membership.domain.PointsLedgerEntry;
import com.example.monkey.membership.domain.PointsLedgerType;
import com.example.monkey.membership.domain.PointsWallet;
import com.example.monkey.membership.domain.PurchasePointsLifecycle;
import com.example.monkey.membership.domain.PurchaseRewardEvent;
import com.example.monkey.membership.domain.PurchaseRewardEventType;
import com.example.monkey.membership.domain.PurchaseRewardFact;
import com.example.monkey.shared.application.tenant.TenantContext;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.shared.domain.id.IdGenerator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Applies payment rewards and refund clawbacks from authoritative payment facts. Every mutation is local to the
 * membership transaction and is guarded by a durable reward fact plus immutable event evidence.
 */
@Service
public class MembershipPurchasePointsService implements PurchasePointsLifecycle {

    private static final int MAX_EVENT_KEY_LENGTH = 256;
    private static final String AWARD_LEDGER_PREFIX = "purchase-award:";
    private static final String REFUND_LEDGER_PREFIX = "purchase-refund:";

    private final MembershipStore membershipStore;
    private final IdGenerator idGenerator;
    private final Clock clock;

    @Autowired
    public MembershipPurchasePointsService(MembershipStore membershipStore, IdGenerator idGenerator) {
        this(membershipStore, idGenerator, Clock.systemUTC());
    }

    MembershipPurchasePointsService(MembershipStore membershipStore, IdGenerator idGenerator, Clock clock) {
        this.membershipStore = Objects.requireNonNull(membershipStore, "membershipStore");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Override
    @Transactional
    public void onPaymentSucceeded(PurchasePointsLifecycle.PurchasePayment payment) {
        long tenantId = requireTenant(payment == null ? null : payment.tenantId());
        requirePaymentIds(payment);
        BigDecimal paidAmount = money(payment.paidAmount());
        requirePositiveAmount(paidAmount, "paid amount");
        String eventKey = requireEventKey(payment.eventKey());
        String fingerprint = awardFingerprint(tenantId, payment, paidAmount);

        Optional<PurchaseRewardEvent> event = findEvent(payment.paymentId(), eventKey);
        if (event.isPresent()) {
            assertEvent(event.get(), PurchaseRewardEventType.AWARD, payment, fingerprint);
            return;
        }

        PurchaseRewardFact existing = membershipStore.findPurchaseReward(payment.paymentId()).orElse(null);
        if (existing != null) {
            assertFact(existing, payment, paidAmount);
            membershipStore.savePurchaseRewardEvent(awardEvent(existing, payment, fingerprint));
            return;
        }

        Optional<PointsLedgerEntry> existingLedger = membershipStore.findLedger(
                payment.userId(), AWARD_LEDGER_PREFIX + payment.paymentId());
        if (existingLedger.isPresent()) {
            throw conflict("Purchase reward ledger exists without a durable reward fact");
        }

        MemberProfile profile = profile(payment.userId());
        PointsWallet wallet = wallet(payment.userId());
        long points = calculateAwardedPoints(paidAmount, profile.level());
        PointsWallet updatedWallet = wallet.apply(points, now());
        if (!membershipStore.updateWallet(updatedWallet)) {
            throw conflict("Points wallet changed concurrently");
        }

        PurchaseRewardFact fact = new PurchaseRewardFact(
                idGenerator.nextId(),
                payment.paymentId(),
                payment.orderId(),
                payment.userId(),
                paidAmount,
                trim(payment.providerTradeNo()),
                profile.level().pointsMultiplier(),
                points,
                0,
                BigDecimal.ZERO.setScale(2),
                eventKey,
                fingerprint,
                0,
                now(),
                now());
        PurchaseRewardFact savedFact = Optional.ofNullable(membershipStore.savePurchaseReward(fact)).orElse(fact);
        membershipStore.saveLedger(new PointsLedgerEntry(
                idGenerator.nextId(),
                payment.userId(),
                PointsLedgerType.PURCHASE,
                points,
                MembershipDtoAssembler.moneyEquivalent(points),
                payment.orderId(),
                "payment:" + payment.paymentId(),
                AWARD_LEDGER_PREFIX + payment.paymentId(),
                fingerprint,
                now()));
        membershipStore.saveProfile(profile.addGrowth(points, now()));
        membershipStore.savePurchaseRewardEvent(awardEvent(savedFact, payment, fingerprint));
    }

    @Override
    @Transactional
    public void onRefundSucceeded(PurchasePointsLifecycle.PurchaseRefund refund) {
        long tenantId = requireTenant(refund == null ? null : refund.tenantId());
        requireRefundIds(refund);
        BigDecimal originalPaidAmount = money(refund.originalPaidAmount());
        BigDecimal refundAmount = money(refund.refundAmount());
        BigDecimal cumulativeRefundedAmount = money(refund.cumulativeRefundedAmount());
        requirePositiveAmount(originalPaidAmount, "original paid amount");
        requirePositiveAmount(refundAmount, "refund amount");
        if (cumulativeRefundedAmount.compareTo(refundAmount) < 0
                || cumulativeRefundedAmount.compareTo(originalPaidAmount) > 0) {
            throw conflict("Cumulative refund amount is invalid");
        }
        String eventKey = requireEventKey(refund.eventKey());
        String fingerprint = refundFingerprint(tenantId, refund, originalPaidAmount, refundAmount, cumulativeRefundedAmount);

        Optional<PurchaseRewardEvent> event = findEvent(refund.paymentId(), eventKey);
        if (event.isPresent()) {
            assertEvent(event.get(), PurchaseRewardEventType.REFUND, refund, fingerprint);
            return;
        }

        PurchaseRewardFact fact = membershipStore
                .findPurchaseReward(refund.paymentId())
                .orElseThrow(() -> conflict("Purchase reward fact is missing for the refunded payment"));
        assertFact(fact, refund, originalPaidAmount);
        if (cumulativeRefundedAmount.compareTo(fact.cumulativeRefundedAmount()) < 0) {
            throw conflict("Refund event is older than already applied refund progress");
        }
        long targetReversedPoints = targetReversedPoints(fact, cumulativeRefundedAmount);
        if (targetReversedPoints < fact.reversedPoints()) {
            throw conflict("Refund event would reduce recorded point reversal");
        }
        long delta = targetReversedPoints - fact.reversedPoints();
        if (delta > 0) {
            PointsWallet wallet = wallet(refund.userId());
            PointsWallet updatedWallet = wallet.applyRefundReversal(delta, now());
            if (!membershipStore.updateWallet(updatedWallet)) {
                throw conflict("Points wallet changed concurrently");
            }
            MemberProfile profile = profile(refund.userId());
            membershipStore.saveProfile(profile.addGrowth(-delta, now()));
            membershipStore.saveLedger(new PointsLedgerEntry(
                    idGenerator.nextId(),
                    refund.userId(),
                    PointsLedgerType.PURCHASE_REFUND,
                    -delta,
                    MembershipDtoAssembler.moneyEquivalent(delta),
                    refund.orderId(),
                    "payment-refund:" + refund.paymentId(),
                    REFUND_LEDGER_PREFIX + refund.paymentId() + ":" + sha256Hex(eventKey),
                    fingerprint,
                    now()));
        }

        PurchaseRewardFact updatedFact = fact.withRefundProgress(
                targetReversedPoints, cumulativeRefundedAmount, now());
        PurchaseRewardFact savedFact = Optional.ofNullable(membershipStore.savePurchaseReward(updatedFact))
                .orElse(updatedFact);
        membershipStore.savePurchaseRewardEvent(new PurchaseRewardEvent(
                idGenerator.nextId(),
                savedFact.id(),
                refund.paymentId(),
                refund.orderId(),
                refund.userId(),
                PurchaseRewardEventType.REFUND,
                eventKey,
                fingerprint,
                refundAmount,
                cumulativeRefundedAmount,
                targetReversedPoints,
                delta,
                now()));
    }

    private Optional<PurchaseRewardEvent> findEvent(Long paymentId, String eventKey) {
        Optional<PurchaseRewardEvent> byKey = membershipStore.findPurchaseRewardEventByKey(eventKey);
        if (byKey.isPresent()) {
            if (!paymentId.equals(byKey.get().paymentId())) {
                throw conflict("Purchase reward event key is already bound to another payment");
            }
            return byKey;
        }
        return membershipStore.findPurchaseRewardEvent(paymentId, eventKey);
    }

    private PurchaseRewardEvent awardEvent(
            PurchaseRewardFact fact, PurchasePointsLifecycle.PurchasePayment payment, String fingerprint) {
        return new PurchaseRewardEvent(
                idGenerator.nextId(),
                fact.id(),
                payment.paymentId(),
                payment.orderId(),
                payment.userId(),
                PurchaseRewardEventType.AWARD,
                requireEventKey(payment.eventKey()),
                fingerprint,
                BigDecimal.ZERO.setScale(2),
                BigDecimal.ZERO.setScale(2),
                0,
                0,
                now());
    }

    private static long targetReversedPoints(PurchaseRewardFact fact, BigDecimal cumulativeRefundedAmount) {
        if (cumulativeRefundedAmount.compareTo(fact.originalPaidAmount()) >= 0) {
            return fact.awardedPoints();
        }
        BigDecimal target = BigDecimal.valueOf(fact.awardedPoints())
                .multiply(cumulativeRefundedAmount)
                .divide(fact.originalPaidAmount(), 0, RoundingMode.HALF_UP);
        return Math.min(fact.awardedPoints(), Math.max(0, target.longValueExact()));
    }

    private MemberProfile profile(Long userId) {
        return membershipStore.findProfile(userId).orElseGet(() -> membershipStore.saveProfile(new MemberProfile(
                idGenerator.nextId(),
                userId,
                MembershipLevel.BASIC,
                0,
                null,
                null,
                null,
                null,
                null,
                0,
                now(),
                now())));
    }

    private PointsWallet wallet(Long userId) {
        return membershipStore.findWallet(userId).orElseGet(() -> membershipStore.saveWallet(
                new PointsWallet(idGenerator.nextId(), userId, 0, 0, 0, 0, 0, now(), now())));
    }

    private static long calculateAwardedPoints(BigDecimal paidAmount, MembershipLevel level) {
        try {
            long basePoints = paidAmount.setScale(0, RoundingMode.DOWN).longValueExact();
            long points = Math.multiplyExact(basePoints, level.pointsMultiplier());
            return Math.max(1, points);
        } catch (ArithmeticException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Payment amount is too large for points reward");
        }
    }

    private static void assertFact(
            PurchaseRewardFact fact, PurchasePointsLifecycle.PurchasePayment payment, BigDecimal paidAmount) {
        if (!Objects.equals(fact.paymentId(), payment.paymentId())
                || !Objects.equals(fact.orderId(), payment.orderId())
                || !Objects.equals(fact.userId(), payment.userId())
                || fact.originalPaidAmount().compareTo(paidAmount) != 0
                || (StringUtils.hasText(fact.providerTradeNo())
                        && StringUtils.hasText(payment.providerTradeNo())
                        && !fact.providerTradeNo().equals(payment.providerTradeNo()))) {
            throw conflict("Payment success facts conflict with the durable purchase reward");
        }
    }

    private static void assertFact(
            PurchaseRewardFact fact, PurchasePointsLifecycle.PurchaseRefund refund, BigDecimal originalPaidAmount) {
        if (!Objects.equals(fact.paymentId(), refund.paymentId())
                || !Objects.equals(fact.orderId(), refund.orderId())
                || !Objects.equals(fact.userId(), refund.userId())
                || fact.originalPaidAmount().compareTo(originalPaidAmount) != 0
                || (StringUtils.hasText(fact.providerTradeNo())
                        && StringUtils.hasText(refund.providerTradeNo())
                        && !fact.providerTradeNo().equals(refund.providerTradeNo()))) {
            throw conflict("Refund facts conflict with the durable purchase reward");
        }
    }

    private static void assertEvent(
            PurchaseRewardEvent event,
            PurchaseRewardEventType expectedType,
            PurchasePointsLifecycle.PurchasePayment payment,
            String expectedFingerprint) {
        if (event.type() != expectedType
                || !Objects.equals(event.paymentId(), payment.paymentId())
                || !Objects.equals(event.orderId(), payment.orderId())
                || !Objects.equals(event.userId(), payment.userId())
                || !constantTimeEquals(event.fingerprint(), expectedFingerprint)) {
            throw conflict("Purchase reward event fingerprint does not match");
        }
    }

    private static void assertEvent(
            PurchaseRewardEvent event,
            PurchaseRewardEventType expectedType,
            PurchasePointsLifecycle.PurchaseRefund refund,
            String expectedFingerprint) {
        if (event.type() != expectedType
                || !Objects.equals(event.paymentId(), refund.paymentId())
                || !Objects.equals(event.orderId(), refund.orderId())
                || !Objects.equals(event.userId(), refund.userId())
                || !constantTimeEquals(event.fingerprint(), expectedFingerprint)) {
            throw conflict("Purchase reward event fingerprint does not match");
        }
    }

    private static long requireTenant(Long tenantId) {
        if (tenantId == null || tenantId <= 0 || tenantId.longValue() != TenantContext.currentTenantIdOrDefault()) {
            throw conflict("Purchase reward tenant does not match the current tenant");
        }
        return tenantId;
    }

    private static void requirePaymentIds(PurchasePointsLifecycle.PurchasePayment payment) {
        if (payment == null || payment.paymentId() == null || payment.orderId() == null || payment.userId() == null
                || payment.paymentId() <= 0 || payment.orderId() <= 0 || payment.userId() <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Payment reward ownership facts are required");
        }
    }

    private static void requireRefundIds(PurchasePointsLifecycle.PurchaseRefund refund) {
        if (refund == null || refund.paymentId() == null || refund.orderId() == null || refund.userId() == null
                || refund.paymentId() <= 0 || refund.orderId() <= 0 || refund.userId() <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Refund reward ownership facts are required");
        }
    }

    private static void requirePositiveAmount(BigDecimal amount, String description) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, description + " must be positive");
        }
    }

    private static String requireEventKey(String eventKey) {
        if (!StringUtils.hasText(eventKey) || eventKey.trim().length() > MAX_EVENT_KEY_LENGTH) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Purchase reward event key is invalid");
        }
        return eventKey.trim();
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private static String awardFingerprint(
            long tenantId, PurchasePointsLifecycle.PurchasePayment payment, BigDecimal paidAmount) {
        return sha256Hex(String.join(
                "|",
                "AWARD",
                String.valueOf(tenantId),
                String.valueOf(payment.paymentId()),
                String.valueOf(payment.orderId()),
                String.valueOf(payment.userId()),
                paidAmount.toPlainString(),
                canonical(payment.providerTradeNo())));
    }

    private static String refundFingerprint(
            long tenantId,
            PurchasePointsLifecycle.PurchaseRefund refund,
            BigDecimal originalPaidAmount,
            BigDecimal refundAmount,
            BigDecimal cumulativeRefundedAmount) {
        return sha256Hex(String.join(
                "|",
                "REFUND",
                String.valueOf(tenantId),
                String.valueOf(refund.paymentId()),
                String.valueOf(refund.orderId()),
                String.valueOf(refund.userId()),
                originalPaidAmount.toPlainString(),
                refundAmount.toPlainString(),
                cumulativeRefundedAmount.toPlainString(),
                canonical(refund.providerTradeNo())));
    }

    private static String canonical(String value) {
        String normalized = trim(value);
        return normalized == null ? "" : normalized;
    }

    private static String trim(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static boolean constantTimeEquals(String left, String right) {
        return left != null
                && right != null
                && MessageDigest.isEqual(
                        left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII));
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message);
    }
}
