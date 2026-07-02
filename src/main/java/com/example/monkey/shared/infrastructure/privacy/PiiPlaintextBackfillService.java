package com.example.monkey.shared.infrastructure.privacy;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class PiiPlaintextBackfillService {

    private static final String ENCRYPTED_LIKE = PiiCryptoService.ENCRYPTION_PREFIX + "%";

    private final JdbcTemplate jdbcTemplate;
    private final PiiCryptoService piiCryptoService;

    public PiiPlaintextBackfillService(JdbcTemplate jdbcTemplate, PiiCryptoService piiCryptoService) {
        this.jdbcTemplate = jdbcTemplate;
        this.piiCryptoService = piiCryptoService;
    }

    @Transactional
    public BackfillReport backfillLegacyPlaintext() {
        if (!piiCryptoService.encryptionEnabled()) {
            throw new IllegalStateException("PII plaintext backfill requires APP_PII_ENCRYPTION_ENABLED=true");
        }
        int users = backfillUsers();
        int addresses = backfillAddresses();
        int orders = backfillOrders();
        return new BackfillReport(users, addresses, orders);
    }

    private int backfillUsers() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT `id`, `phone`, `email`, `phone_hmac`
                FROM `user`
                WHERE (`phone` IS NOT NULL AND `phone` NOT LIKE ?)
                   OR (`email` IS NOT NULL AND `email` NOT LIKE ?)
                """, ENCRYPTED_LIKE, ENCRYPTED_LIKE);
        int updated = 0;
        for (Map<String, Object> row : rows) {
            String phone = asString(row.get("phone"));
            String email = asString(row.get("email"));
            String phoneHmac = asString(row.get("phone_hmac"));
            String encryptedPhone = encryptIfPlaintext(phone);
            String encryptedEmail = encryptIfPlaintext(email);
            String nextPhoneHmac = blindIndexIfPlaintextPhone(phone, phoneHmac);
            if (changed(phone, encryptedPhone) || changed(email, encryptedEmail) || changed(phoneHmac, nextPhoneHmac)) {
                updated += jdbcTemplate.update(
                        "UPDATE `user` SET `phone` = ?, `email` = ?, `phone_hmac` = ? WHERE `id` = ?",
                        encryptedPhone,
                        encryptedEmail,
                        nextPhoneHmac,
                        row.get("id"));
            }
        }
        return updated;
    }

    private int backfillAddresses() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT `id`, `receiver_name`, `phone`, `phone_hmac`, `detail_address`
                FROM `address`
                WHERE (`receiver_name` IS NOT NULL AND `receiver_name` NOT LIKE ?)
                   OR (`phone` IS NOT NULL AND `phone` NOT LIKE ?)
                   OR (`detail_address` IS NOT NULL AND `detail_address` NOT LIKE ?)
                """, ENCRYPTED_LIKE, ENCRYPTED_LIKE, ENCRYPTED_LIKE);
        int updated = 0;
        for (Map<String, Object> row : rows) {
            String receiverName = asString(row.get("receiver_name"));
            String phone = asString(row.get("phone"));
            String phoneHmac = asString(row.get("phone_hmac"));
            String detailAddress = asString(row.get("detail_address"));
            String encryptedReceiverName = encryptIfPlaintext(receiverName);
            String encryptedPhone = encryptIfPlaintext(phone);
            String nextPhoneHmac = blindIndexIfPlaintextPhone(phone, phoneHmac);
            String encryptedDetailAddress = encryptIfPlaintext(detailAddress);
            if (changed(receiverName, encryptedReceiverName)
                    || changed(phone, encryptedPhone)
                    || changed(phoneHmac, nextPhoneHmac)
                    || changed(detailAddress, encryptedDetailAddress)) {
                updated += jdbcTemplate.update(
                        """
                        UPDATE `address`
                        SET `receiver_name` = ?, `phone` = ?, `phone_hmac` = ?, `detail_address` = ?
                        WHERE `id` = ?
                        """,
                        encryptedReceiverName,
                        encryptedPhone,
                        nextPhoneHmac,
                        encryptedDetailAddress,
                        row.get("id"));
            }
        }
        return updated;
    }

    private int backfillOrders() {
        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList("""
                SELECT `id`, `buyer_name`, `receiver_name`, `receiver_phone`, `receiver_phone_hmac`, `address_snapshot`
                FROM `orders`
                WHERE (`buyer_name` IS NOT NULL AND `buyer_name` NOT LIKE ?)
                   OR (`receiver_name` IS NOT NULL AND `receiver_name` NOT LIKE ?)
                   OR (`receiver_phone` IS NOT NULL AND `receiver_phone` NOT LIKE ?)
                   OR (`address_snapshot` IS NOT NULL AND `address_snapshot` NOT LIKE ?)
                """, ENCRYPTED_LIKE, ENCRYPTED_LIKE, ENCRYPTED_LIKE, ENCRYPTED_LIKE);
        int updated = 0;
        for (Map<String, Object> row : rows) {
            String buyerName = asString(row.get("buyer_name"));
            String receiverName = asString(row.get("receiver_name"));
            String receiverPhone = asString(row.get("receiver_phone"));
            String receiverPhoneHmac = asString(row.get("receiver_phone_hmac"));
            String addressSnapshot = asString(row.get("address_snapshot"));
            String encryptedBuyerName = encryptIfPlaintext(buyerName);
            String encryptedReceiverName = encryptIfPlaintext(receiverName);
            String encryptedReceiverPhone = encryptIfPlaintext(receiverPhone);
            String nextReceiverPhoneHmac = blindIndexIfPlaintextPhone(receiverPhone, receiverPhoneHmac);
            String encryptedAddressSnapshot = encryptIfPlaintext(addressSnapshot);
            if (changed(buyerName, encryptedBuyerName)
                    || changed(receiverName, encryptedReceiverName)
                    || changed(receiverPhone, encryptedReceiverPhone)
                    || changed(receiverPhoneHmac, nextReceiverPhoneHmac)
                    || changed(addressSnapshot, encryptedAddressSnapshot)) {
                updated += jdbcTemplate.update(
                        """
                        UPDATE `orders`
                        SET `buyer_name` = ?,
                            `receiver_name` = ?,
                            `receiver_phone` = ?,
                            `receiver_phone_hmac` = ?,
                            `address_snapshot` = ?
                        WHERE `id` = ?
                        """,
                        encryptedBuyerName,
                        encryptedReceiverName,
                        encryptedReceiverPhone,
                        nextReceiverPhoneHmac,
                        encryptedAddressSnapshot,
                        row.get("id"));
            }
        }
        return updated;
    }

    private String encryptIfPlaintext(String value) {
        if (!StringUtils.hasText(value) || value.startsWith(PiiCryptoService.ENCRYPTION_PREFIX)) {
            return value;
        }
        return piiCryptoService.encrypt(value);
    }

    private String blindIndexIfPlaintextPhone(String phone, String currentBlindIndex) {
        if (!StringUtils.hasText(phone)) {
            return null;
        }
        if (phone.startsWith(PiiCryptoService.ENCRYPTION_PREFIX)) {
            return currentBlindIndex;
        }
        return piiCryptoService.blindIndexPhone(phone);
    }

    private static boolean changed(String before, String after) {
        return !Objects.equals(before, after);
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    public record BackfillReport(int users, int addresses, int orders) {

        public int total() {
            return users + addresses + orders;
        }
    }
}
