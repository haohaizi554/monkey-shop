package com.example.monkey.logistics.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.monkey.logistics.domain.LogisticsCarrier;
import com.example.monkey.logistics.domain.LogisticsTracking;
import com.example.monkey.logistics.domain.TrackingStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class SandboxLogisticsGatewayTest {

    @Test
    void createShipmentEchoesCarrierTrackingNoStatusAndEta() {
        var result = new SandboxLogisticsGateway().createShipment(tracking());

        assertThat(result.carrier()).isEqualTo(LogisticsCarrier.YTO);
        assertThat(result.trackingNo()).isEqualTo("YTO7000");
        assertThat(result.status()).isEqualTo(TrackingStatus.ORDERED);
        assertThat(result.etaHours()).isEqualTo(48);
        assertThat(result.acceptedAt()).isEqualTo(LocalDateTime.parse("2026-07-04T08:00:00"));
    }

    private static LogisticsTracking tracking() {
        return new LogisticsTracking(
                7000L,
                "YTO7000",
                10L,
                42L,
                LogisticsCarrier.YTO,
                TrackingStatus.ORDERED,
                null,
                null,
                null,
                null,
                "Hainan",
                "Haikou",
                "Longhua",
                "Hainan Haikou Longhua",
                new BigDecimal("18.00"),
                48,
                "ship-key",
                null,
                null,
                null,
                null,
                LocalDateTime.parse("2026-07-04T08:00:00"),
                LocalDateTime.parse("2026-07-04T08:00:00"));
    }
}
