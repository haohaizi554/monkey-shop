package com.example.monkey.risk.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

public final class RiskPolicy {

    public static final int HIGH_RISK_THRESHOLD = 80;
    public static final int BLOCK_THRESHOLD = 95;
    public static final BigDecimal PRICE_ANOMALY_RATE = BigDecimal.valueOf(50, 2);

    private RiskPolicy() {}

    public static RiskAssessment assess(RiskPolicyInput input) {
        RiskPolicyInput normalized = input == null ? RiskPolicyInput.empty() : input;
        List<RiskSignal> signals = new ArrayList<>();
        if (normalized.distinctUsersOnDevice() >= 3) {
            signals.add(new RiskSignal(
                    RiskSignalType.DEVICE_MULTI_ACCOUNT,
                    cappedWeight(normalized.distinctUsersOnDevice(), 8, 35),
                    "device users=" + normalized.distinctUsersOnDevice()));
        }
        if (normalized.distinctPhonesOnDevice() >= 2) {
            signals.add(new RiskSignal(
                    RiskSignalType.PHONE_MULTI_ACCOUNT,
                    cappedWeight(normalized.distinctPhonesOnDevice(), 10, 30),
                    "device phones=" + normalized.distinctPhonesOnDevice()));
        }
        if (normalized.seckillUsersOnDevice() >= 3) {
            signals.add(new RiskSignal(
                    RiskSignalType.SECKILL_SCALPER,
                    cappedWeight(normalized.seckillUsersOnDevice(), 12, 45),
                    "seckill users=" + normalized.seckillUsersOnDevice()));
        }
        if (normalized.selfBuy()) {
            signals.add(new RiskSignal(RiskSignalType.SELF_BUY, 35, "buyer matches seller"));
        }
        if (priceAnomaly(normalized.priceBefore(), normalized.priceAfter())) {
            signals.add(new RiskSignal(
                    RiskSignalType.PRICE_ANOMALY,
                    65,
                    "price change=" + priceChangeRate(normalized.priceBefore(), normalized.priceAfter())));
        }

        int score = Math.min(100, signals.stream().mapToInt(RiskSignal::weight).sum());
        RiskDecision decision = decision(score, normalized.totpVerified(), signals);
        if (score >= HIGH_RISK_THRESHOLD) {
            signals.add(new RiskSignal(RiskSignalType.HIGH_RISK_SCORE, 0, "score=" + score));
        }
        if (decision == RiskDecision.BLOCK) {
            signals.add(new RiskSignal(RiskSignalType.ACCOUNT_BLOCKED, 0, "score=" + score));
        }
        return new RiskAssessment(score, decision, signals);
    }

    private static int cappedWeight(long count, int perUnit, int cap) {
        if (count <= 0) {
            return 0;
        }
        long capThreshold = (cap + perUnit - 1L) / perUnit;
        if (count >= capThreshold) {
            return cap;
        }
        return Math.toIntExact(count * perUnit);
    }

    public static boolean priceAnomaly(BigDecimal before, BigDecimal after) {
        return priceChangeRate(before, after).compareTo(PRICE_ANOMALY_RATE) > 0;
    }

    static BigDecimal priceChangeRate(BigDecimal before, BigDecimal after) {
        if (before == null || after == null || before.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return after.subtract(before).abs().divide(before, 4, RoundingMode.HALF_UP);
    }

    private static RiskDecision decision(int score, boolean totpVerified, List<RiskSignal> signals) {
        boolean scalper = signals.stream().anyMatch(signal -> signal.type() == RiskSignalType.SECKILL_SCALPER);
        if (score >= BLOCK_THRESHOLD || (scalper && score >= HIGH_RISK_THRESHOLD)) {
            return RiskDecision.BLOCK;
        }
        if (score >= HIGH_RISK_THRESHOLD) {
            return totpVerified ? RiskDecision.RATE_LIMIT : RiskDecision.TOTP_REQUIRED;
        }
        if (score >= 60) {
            return RiskDecision.REVIEW;
        }
        return RiskDecision.ALLOW;
    }

    public record RiskPolicyInput(
            long distinctUsersOnDevice,
            long distinctPhonesOnDevice,
            long seckillUsersOnDevice,
            boolean selfBuy,
            BigDecimal priceBefore,
            BigDecimal priceAfter,
            boolean totpVerified) {
        private static RiskPolicyInput empty() {
            return new RiskPolicyInput(0, 0, 0, false, null, null, false);
        }
    }
}
