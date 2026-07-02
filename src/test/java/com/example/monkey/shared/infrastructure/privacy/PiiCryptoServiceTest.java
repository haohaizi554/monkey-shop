package com.example.monkey.shared.infrastructure.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.example.monkey.shared.domain.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class PiiCryptoServiceTest {

    @Test
    void encryptsAndDecryptsWithAesGcm() {
        PiiCryptoService service = enabledService();

        String ciphertext = service.encrypt("13800000000");

        assertThat(ciphertext).startsWith(PiiCryptoService.ENCRYPTION_PREFIX);
        assertThat(ciphertext).contains(":" + PiiCryptoService.TINK_MARKER + ":");
        assertThat(ciphertext).doesNotContain("13800000000");
        assertThat(service.decrypt(ciphertext)).isEqualTo("13800000000");
    }

    @Test
    void disabledEncryptionLeavesValuesUnchanged() {
        PiiCryptoService service = new PiiCryptoService(false, null, null, "v1", true);

        assertThat(service.encryptionEnabled()).isFalse();
        assertThat(service.encrypt("receiver")).isEqualTo("receiver");
        assertThat(service.decrypt("receiver")).isEqualTo("receiver");
    }

    @Test
    void blankOrAlreadyEncryptedValuesAreNotReEncrypted() {
        PiiCryptoService service = enabledService();
        String ciphertext = service.encrypt("receiver");

        assertThat(service.encrypt(" ")).isEqualTo(" ");
        assertThat(service.encrypt(ciphertext)).isEqualTo(ciphertext);
    }

    @Test
    void malformedCiphertextIsRejected() {
        PiiCryptoService service = enabledService();

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> service.decrypt(PiiCryptoService.ENCRYPTION_PREFIX + "broken"))
                .withMessage("PII ciphertext format is invalid");
        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> service.decrypt(PiiCryptoService.ENCRYPTION_PREFIX + "v1:tink:not-base64"))
                .withMessage("PII decryption failed");
    }

    @Test
    void decryptsLegacyAesGcmCiphertext() throws Exception {
        byte[] aesKey = new byte[32];
        byte[] iv = new byte[12];
        for (int i = 0; i < iv.length; i++) {
            iv[i] = (byte) (i + 1);
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new GCMParameterSpec(128, iv));
        cipher.updateAAD("v1".getBytes(StandardCharsets.UTF_8));
        byte[] ciphertext = cipher.doFinal("legacy receiver".getBytes(StandardCharsets.UTF_8));
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String stored = PiiCryptoService.ENCRYPTION_PREFIX
                + "v1:"
                + encoder.encodeToString(iv) + ":"
                + encoder.encodeToString(ciphertext);

        PiiCryptoService service = enabledService();

        assertThat(service.decrypt(stored)).isEqualTo("legacy receiver");
    }

    @Test
    void rotatedKeyReadsPreviousVersionAndWritesWithActiveVersion() {
        byte[] previousAesKey = filled(32, 1);
        byte[] activeAesKey = filled(32, 2);
        SecretKeySpec hmacKey = new SecretKeySpec(filled(32, 3), "HmacSHA256");
        PiiCryptoService previousService = new PiiCryptoService(
                true, new PiiKeyMaterialProvider.PiiKeyMaterial(previousAesKey, hmacKey), "v1", false);
        String previousCiphertext = previousService.encrypt("13800000000");
        PiiCryptoService rotatedService = new PiiCryptoService(
                true,
                new PiiKeyMaterialProvider.PiiKeyMaterial(activeAesKey, hmacKey, Map.of("v1", previousAesKey)),
                "v2",
                false);

        String activeCiphertext = rotatedService.encrypt("13900000000");

        assertThat(previousCiphertext).startsWith(PiiCryptoService.ENCRYPTION_PREFIX + "v1:");
        assertThat(rotatedService.decrypt(previousCiphertext)).isEqualTo("13800000000");
        assertThat(activeCiphertext).startsWith(PiiCryptoService.ENCRYPTION_PREFIX + "v2:");
        assertThat(rotatedService.decrypt(activeCiphertext)).isEqualTo("13900000000");
    }

    @Test
    void rotatedKeyRejectsPreviousVersionWhenHistoryKeyIsMissing() {
        byte[] previousAesKey = filled(32, 1);
        byte[] activeAesKey = filled(32, 2);
        SecretKeySpec hmacKey = new SecretKeySpec(filled(32, 3), "HmacSHA256");
        PiiCryptoService previousService = new PiiCryptoService(
                true, new PiiKeyMaterialProvider.PiiKeyMaterial(previousAesKey, hmacKey), "v1", false);
        String previousCiphertext = previousService.encrypt("13800000000");
        PiiCryptoService rotatedService = new PiiCryptoService(
                true, new PiiKeyMaterialProvider.PiiKeyMaterial(activeAesKey, hmacKey), "v2", false);

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> rotatedService.decrypt(previousCiphertext))
                .withMessage("PII decryption failed");
    }

    @Test
    void blindIndexNormalizesPhoneNumbers() {
        PiiCryptoService service = enabledService();

        assertThat(service.blindIndexPhone("+86 138-0000-0000")).isEqualTo(service.blindIndexPhone("8613800000000"));
    }

    @Test
    void blindIndexHandlesBlankAndNonNumericValues() {
        PiiCryptoService service = enabledService();

        assertThat(service.blindIndexPhone(" ")).isNull();
        assertThat(service.blindIndexPhone("Extension-A")).isEqualTo(service.blindIndexPhone(" extension-a "));
    }

    @Test
    void blindIndexReturnsNullWithoutHmacKey() {
        PiiCryptoService service = new PiiCryptoService(true, new SecretKeySpec(new byte[32], "AES"), null, "v1", true);

        assertThat(service.blindIndexPhone("13800000000")).isNull();
    }

    @Test
    void requiredEncryptionRejectsMissingOrInvalidAesKey() {
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() ->
                        new PiiCryptoService(true, null, new SecretKeySpec(new byte[32], "HmacSHA256"), "v1", true))
                .withMessage("PII AES key is not configured");
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> new PiiCryptoService(
                        true,
                        new SecretKeySpec(new byte[3], "AES"),
                        new SecretKeySpec(new byte[32], "HmacSHA256"),
                        "v1",
                        true))
                .withMessage("PII AES key must be 128, 192, or 256 bits");
    }

    @Test
    void plaintextReadCanBeDisabled() {
        PiiCryptoService service = new PiiCryptoService(
                true,
                new SecretKeySpec(new byte[32], "AES"),
                new SecretKeySpec(new byte[32], "HmacSHA256"),
                "v1",
                false);

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> service.decrypt("13800000000"))
                .withMessage("PII plaintext read is not allowed");
    }

    @Test
    void attributeConverterDelegatesToCryptoService() {
        EncryptedStringAttributeConverter converter = new EncryptedStringAttributeConverter(enabledService());

        String databaseValue = converter.convertToDatabaseColumn("receiver");

        assertThat(databaseValue).startsWith(PiiCryptoService.ENCRYPTION_PREFIX);
        assertThat(converter.convertToEntityAttribute(databaseValue)).isEqualTo("receiver");
    }

    private static byte[] filled(int size, int value) {
        byte[] bytes = new byte[size];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) value;
        }
        return bytes;
    }

    private static PiiCryptoService enabledService() {
        return new PiiCryptoService(
                true,
                new SecretKeySpec(new byte[32], "AES"),
                new SecretKeySpec(new byte[32], "HmacSHA256"),
                "v1",
                true);
    }
}
