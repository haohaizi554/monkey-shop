package com.example.monkey.shared.infrastructure.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.example.monkey.shared.domain.privacy.PhoneBlindIndexTarget;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PiiBlindIndexEntityListenerTest {

    @Test
    void hashesPhoneFieldsForPiiEntities() {
        PiiCryptoService cryptoService = enabledService();
        PiiBlindIndexEntityListener listener = new PiiBlindIndexEntityListener(cryptoService);
        String expectedHash = cryptoService.blindIndexPhone("+86 138-0000-0000");
        TestPhoneBlindIndexTarget target = new TestPhoneBlindIndexTarget("+86 138-0000-0000");

        listener.beforeSave(target);

        assertThat(target.blindIndex).isEqualTo(expectedHash);
    }

    @Test
    void skipsNullAndUnsupportedEntities() {
        PiiBlindIndexEntityListener listener = new PiiBlindIndexEntityListener(enabledService());

        assertThatNoException().isThrownBy(() -> {
            listener.beforeSave(null);
            listener.beforeSave(new Object());
        });
    }

    @Test
    void noArgJpaConstructorDoesNothingUntilSpringInjectsCryptoService() throws Exception {
        AtomicReference<PiiCryptoService> serviceReference = serviceReference();
        PiiCryptoService previousService = serviceReference.get();
        serviceReference.set(null);
        try {
            PiiBlindIndexEntityListener listener = new PiiBlindIndexEntityListener();
            TestPhoneBlindIndexTarget target = new TestPhoneBlindIndexTarget("+86 138-0000-0000");

            listener.beforeSave(target);

            assertThat(target.blindIndex).isNull();
        } finally {
            serviceReference.set(previousService);
        }
    }

    @SuppressWarnings("unchecked")
    private static AtomicReference<PiiCryptoService> serviceReference()
            throws NoSuchFieldException, IllegalAccessException {
        Field field = PiiBlindIndexEntityListener.class.getDeclaredField("PII_CRYPTO_SERVICE");
        field.setAccessible(true);
        return (AtomicReference<PiiCryptoService>) field.get(null);
    }

    private static PiiCryptoService enabledService() {
        PiiKeyMaterialProvider keyMaterialProvider = new PiiKeyMaterialProvider(
                "env",
                base64(new byte[32]),
                base64(new byte[32]),
                "",
                "",
                false,
                Duration.ofDays(90),
                "",
                "",
                "",
                "monkeyshop-pii",
                "",
                "",
                "",
                Duration.ofSeconds(3),
                new ObjectMapper());
        return new PiiCryptoService(true, keyMaterialProvider, "v1", true);
    }

    private static String base64(byte[] value) {
        return Base64.getEncoder().encodeToString(value);
    }

    private static final class TestPhoneBlindIndexTarget implements PhoneBlindIndexTarget {
        private final String phone;
        private String blindIndex;

        private TestPhoneBlindIndexTarget(String phone) {
            this.phone = phone;
        }

        @Override
        public String phoneValueForBlindIndex() {
            return phone;
        }

        @Override
        public void setPhoneBlindIndex(String blindIndex) {
            this.blindIndex = blindIndex;
        }
    }
}
