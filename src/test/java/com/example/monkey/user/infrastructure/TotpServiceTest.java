package com.example.monkey.user.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class TotpServiceTest {

    private static final String RFC_6238_SECRET =
            String.join("", "GEZD", "GNBV", "GY3T", "QOJQ", "GEZD", "GNBV", "GY3T", "QOJQ");

    @Test
    void verifiesSixDigitTotpCodeForCurrentTimeWindow() {
        TotpService service = new TotpService(Clock.fixed(Instant.ofEpochSecond(59), ZoneOffset.UTC));

        assertThat(service.verifyCode(RFC_6238_SECRET, "287082")).isTrue();
    }

    @Test
    void rejectsInvalidTotpCode() {
        TotpService service = new TotpService(Clock.fixed(Instant.ofEpochSecond(59), ZoneOffset.UTC));

        assertThat(service.verifyCode(RFC_6238_SECRET, "000000")).isFalse();
    }

    @Test
    void validatesBase32SecretStrength() {
        TotpService service = new TotpService(Clock.fixed(Instant.ofEpochSecond(59), ZoneOffset.UTC));

        assertThat(service.isValidSecret(RFC_6238_SECRET)).isTrue();
        assertThat(service.isValidSecret("bad-secret")).isFalse();
    }
}
