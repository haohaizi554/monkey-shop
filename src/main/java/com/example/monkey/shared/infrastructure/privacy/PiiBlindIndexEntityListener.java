package com.example.monkey.shared.infrastructure.privacy;

import com.example.monkey.shared.domain.privacy.PhoneBlindIndexTarget;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class PiiBlindIndexEntityListener {

    private static final AtomicReference<PiiCryptoService> PII_CRYPTO_SERVICE = new AtomicReference<>();

    public PiiBlindIndexEntityListener() {}

    @Autowired
    public PiiBlindIndexEntityListener(PiiCryptoService piiCryptoService) {
        PII_CRYPTO_SERVICE.set(piiCryptoService);
    }

    @PrePersist
    @PreUpdate
    public void beforeSave(Object entity) {
        PiiCryptoService piiCryptoService = PII_CRYPTO_SERVICE.get();
        if (piiCryptoService == null || entity == null) {
            return;
        }
        if (entity instanceof PhoneBlindIndexTarget target) {
            target.setPhoneBlindIndex(piiCryptoService.blindIndexPhone(target.phoneValueForBlindIndex()));
        }
    }
}
