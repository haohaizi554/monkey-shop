package com.example.monkey.shared.infrastructure.privacy;

import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.google.crypto.tink.Aead;
import com.google.crypto.tink.subtle.AesGcmJce;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class PiiCryptoService {

    static final String ENCRYPTION_PREFIX = "enc:v1:";
    static final String TINK_MARKER = "tink";
    private static final String AES_ALGORITHM = "AES";
    private static final String AES_GCM = "AES/GCM/NoPadding";
    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final int GCM_TAG_BITS = 128;

    private final boolean encryptionEnabled;
    private final boolean allowPlaintextRead;
    private final String keyVersion;
    private final Aead aead;
    private final SecretKey legacyAesKey;
    private final SecretKey hmacKey;

    public PiiCryptoService(
            @Value("${app.pii.encryption.enabled:false}") boolean encryptionEnabled,
            PiiKeyMaterialProvider keyMaterialProvider,
            @Value("${app.pii.encryption.key-version:v1}") String keyVersion,
            @Value("${app.pii.encryption.allow-plaintext-read:true}") boolean allowPlaintextRead) {
        this(encryptionEnabled, keyMaterialProvider.load(encryptionEnabled), keyVersion, allowPlaintextRead);
    }

    PiiCryptoService(
            boolean encryptionEnabled,
            PiiKeyMaterialProvider.PiiKeyMaterial keyMaterial,
            String keyVersion,
            boolean allowPlaintextRead) {
        this(encryptionEnabled, keyMaterial.aesKeyBytes(), keyMaterial.hmacKey(), keyVersion, allowPlaintextRead);
    }

    PiiCryptoService(
            boolean encryptionEnabled,
            SecretKey aesKey,
            SecretKey hmacKey,
            String keyVersion,
            boolean allowPlaintextRead) {
        this(encryptionEnabled, aesKey == null ? null : aesKey.getEncoded(), hmacKey, keyVersion, allowPlaintextRead);
    }

    private PiiCryptoService(
            boolean encryptionEnabled,
            byte[] aesKeyBytes,
            SecretKey hmacKey,
            String keyVersion,
            boolean allowPlaintextRead) {
        this.encryptionEnabled = encryptionEnabled;
        this.aead = createAead(aesKeyBytes, encryptionEnabled);
        this.legacyAesKey = aesKeyBytes == null
                ? null
                : new SecretKeySpec(Arrays.copyOf(aesKeyBytes, aesKeyBytes.length), AES_ALGORITHM);
        this.hmacKey = hmacKey;
        this.keyVersion = StringUtils.hasText(keyVersion) ? keyVersion.trim() : "v1";
        this.allowPlaintextRead = allowPlaintextRead;
    }

    public String encrypt(String plaintext) {
        if (!encryptionEnabled || !StringUtils.hasText(plaintext) || plaintext.startsWith(ENCRYPTION_PREFIX)) {
            return plaintext;
        }
        try {
            byte[] ciphertext = aead.encrypt(
                    plaintext.getBytes(StandardCharsets.UTF_8), keyVersion.getBytes(StandardCharsets.UTF_8));
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            return ENCRYPTION_PREFIX + keyVersion + ":" + TINK_MARKER + ":" + encoder.encodeToString(ciphertext);
        } catch (GeneralSecurityException exception) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "PII encryption failed");
        }
    }

    public String decrypt(String storedValue) {
        if (!encryptionEnabled || !StringUtils.hasText(storedValue)) {
            return storedValue;
        }
        if (!storedValue.startsWith(ENCRYPTION_PREFIX)) {
            if (allowPlaintextRead) {
                return storedValue;
            }
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "PII plaintext read is not allowed");
        }
        String[] parts = storedValue.split(":", 5);
        if (parts.length != 5) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "PII ciphertext format is invalid");
        }
        try {
            String storedKeyVersion = parts[2];
            Base64.Decoder decoder = Base64.getUrlDecoder();
            if (TINK_MARKER.equals(parts[3])) {
                byte[] ciphertext = decoder.decode(parts[4]);
                return new String(
                        aead.decrypt(ciphertext, storedKeyVersion.getBytes(StandardCharsets.UTF_8)),
                        StandardCharsets.UTF_8);
            }
            return decryptLegacyAesGcm(storedKeyVersion, decoder.decode(parts[3]), decoder.decode(parts[4]));
        } catch (IllegalArgumentException | GeneralSecurityException exception) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "PII decryption failed");
        }
    }

    public String blindIndexPhone(String phone) {
        if (!StringUtils.hasText(phone) || hmacKey == null) {
            return null;
        }
        return hmacHex(normalizePhone(phone));
    }

    public boolean encryptionEnabled() {
        return encryptionEnabled;
    }

    private String decryptLegacyAesGcm(String storedKeyVersion, byte[] iv, byte[] ciphertext)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(AES_GCM);
        cipher.init(Cipher.DECRYPT_MODE, legacyAesKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
        cipher.updateAAD(storedKeyVersion.getBytes(StandardCharsets.UTF_8));
        return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    }

    private String hmacHex(String normalizedValue) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(hmacKey);
            return HexFormat.of().formatHex(mac.doFinal(normalizedValue.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "PII blind index failed");
        }
    }

    private static String normalizePhone(String phone) {
        String digits = phone.replaceAll("\\D", "");
        return StringUtils.hasText(digits) ? digits : phone.trim().toLowerCase(Locale.ROOT);
    }

    private static Aead createAead(byte[] keyBytes, boolean required) {
        if (keyBytes == null) {
            if (required) {
                throw new IllegalStateException("PII AES key is not configured");
            }
            return null;
        }
        if (!(keyBytes.length == 16 || keyBytes.length == 24 || keyBytes.length == 32)) {
            throw new IllegalStateException("PII AES key must be 128, 192, or 256 bits");
        }
        try {
            return new AesGcmJce(Arrays.copyOf(keyBytes, keyBytes.length));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("PII AES-GCM primitive could not be initialized", exception);
        }
    }
}
