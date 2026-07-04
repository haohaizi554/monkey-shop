package com.example.monkey.risk.infrastructure;

import com.example.monkey.risk.domain.RiskBlindIndexService;
import com.example.monkey.shared.infrastructure.privacy.PiiCryptoService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class PiiRiskBlindIndexService implements RiskBlindIndexService {

    private final PiiCryptoService piiCryptoService;

    public PiiRiskBlindIndexService(PiiCryptoService piiCryptoService) {
        this.piiCryptoService = piiCryptoService;
    }

    @Override
    public String blindIndex(String value) {
        String hmac = piiCryptoService.blindIndex(value);
        return StringUtils.hasText(hmac) ? hmac : sha256(value);
    }

    @Override
    public String phoneBlindIndex(String value) {
        String hmac = piiCryptoService.blindIndexPhone(value);
        return StringUtils.hasText(hmac) ? hmac : sha256(value);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of()
                    .formatHex(digest.digest((value == null ? "" : value.trim().toLowerCase(Locale.ROOT))
                            .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest is unavailable", exception);
        }
    }
}
