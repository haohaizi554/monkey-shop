package com.example.monkey.user.infrastructure;

import com.example.monkey.user.domain.UserMfaVerifier;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class TotpService implements UserMfaVerifier {

    private static final int TIME_STEP_SECONDS = 30;
    private static final int CODE_DIGITS = 6;
    private static final int VALIDATION_WINDOW_STEPS = 1;
    private static final String HMAC_ALGORITHM = "HmacSHA1";
    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private final Clock clock;

    public TotpService() {
        this(Clock.systemUTC());
    }

    TotpService(Clock clock) {
        this.clock = clock;
    }

    @Override
    public boolean verifyCode(String base32Secret, String code) {
        if (!StringUtils.hasText(base32Secret) || !StringUtils.hasText(code)) {
            return false;
        }
        String normalizedCode = code.trim();
        if (!normalizedCode.matches("\\d{" + CODE_DIGITS + "}")) {
            return false;
        }

        long currentCounter = clock.instant().getEpochSecond() / TIME_STEP_SECONDS;
        for (long offset = -VALIDATION_WINDOW_STEPS; offset <= VALIDATION_WINDOW_STEPS; offset++) {
            if (normalizedCode.equals(generateCode(base32Secret, currentCounter + offset))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isValidSecret(String base32Secret) {
        try {
            return decodeBase32(base32Secret).length >= 10;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    String generateCode(String base32Secret, long counter) {
        try {
            byte[] secret = decodeBase32(base32Secret);
            byte[] counterBytes =
                    ByteBuffer.allocate(Long.BYTES).putLong(counter).array();
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            byte[] hash = mac.doFinal(counterBytes);
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);
            int otp = binary % (int) Math.pow(10, CODE_DIGITS);
            return String.format("%0" + CODE_DIGITS + "d", otp);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to generate TOTP code", e);
        }
    }

    private static byte[] decodeBase32(String encodedSecret) {
        if (!StringUtils.hasText(encodedSecret)) {
            throw new IllegalArgumentException("TOTP secret is required");
        }
        String normalized = encodedSecret.replace(" ", "").replace("=", "").toUpperCase(Locale.ROOT);
        ByteBuffer output = ByteBuffer.allocate(normalized.length() * 5 / 8 + 1);
        int buffer = 0;
        int bitsLeft = 0;
        for (int i = 0; i < normalized.length(); i++) {
            int value = BASE32_ALPHABET.indexOf(normalized.charAt(i));
            if (value < 0) {
                throw new IllegalArgumentException("Invalid Base32 TOTP secret");
            }
            buffer = (buffer << 5) | value;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                output.put((byte) ((buffer >> (bitsLeft - 8)) & 0xff));
                bitsLeft -= 8;
            }
        }
        output.flip();
        byte[] decoded = new byte[output.remaining()];
        output.get(decoded);
        return decoded;
    }
}
