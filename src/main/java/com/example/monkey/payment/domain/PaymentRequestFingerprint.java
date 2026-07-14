package com.example.monkey.payment.domain;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

public record PaymentRequestFingerprint(String value) {

    private static final String DEFAULT_CURRENCY = "CNY";
    private static final Pattern UNICODE_WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final ObjectMapper CANONICAL_JSON = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN)
            .build();

    public PaymentRequestFingerprint {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("payment request fingerprint must be a SHA-256 hex digest");
        }
    }

    public static PaymentRequestFingerprint of(Long orderId, PaymentMethod method, BigDecimal amount, String currency) {
        Map<String, Object> request = new TreeMap<>();
        request.put("orderId", Objects.requireNonNull(orderId, "orderId must not be null"));
        request.put(
                "method",
                Objects.requireNonNull(method, "method must not be null").name().toUpperCase(Locale.ROOT));
        request.put("amount", money(amount));
        request.put("currency", normalizeCurrency(currency));
        return hash(request);
    }

    public static PaymentRequestFingerprint ofRefund(Long paymentId, BigDecimal amount, String normalizedReason) {
        Map<String, Object> request = new TreeMap<>();
        request.put("paymentId", Objects.requireNonNull(paymentId, "paymentId must not be null"));
        request.put("amount", money(amount));
        request.put("reason", normalizeReason(normalizedReason));
        return hash(request);
    }

    private static PaymentRequestFingerprint hash(Map<String, Object> request) {
        try {
            byte[] canonicalJson = CANONICAL_JSON.writeValueAsBytes(request);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return new PaymentRequestFingerprint(HexFormat.of().formatHex(digest.digest(canonicalJson)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Payment request cannot be serialized", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest is unavailable", e);
        }
    }

    private static BigDecimal money(BigDecimal amount) {
        return Objects.requireNonNull(amount, "amount must not be null").setScale(2, RoundingMode.HALF_UP);
    }

    private static String normalizeCurrency(String currency) {
        return currency == null || currency.isBlank()
                ? DEFAULT_CURRENCY
                : currency.strip().toUpperCase(Locale.ROOT);
    }

    private static String normalizeReason(String reason) {
        return reason == null
                ? ""
                : UNICODE_WHITESPACE.matcher(reason).replaceAll(" ").strip();
    }
}
