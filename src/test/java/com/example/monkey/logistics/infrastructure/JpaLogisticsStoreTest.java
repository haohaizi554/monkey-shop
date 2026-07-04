package com.example.monkey.logistics.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.logistics.domain.FreightChargeMode;
import com.example.monkey.logistics.domain.FreightTemplate;
import com.example.monkey.logistics.domain.LogisticsCarrier;
import com.example.monkey.logistics.domain.LogisticsTracking;
import com.example.monkey.logistics.domain.TrackingEvent;
import com.example.monkey.logistics.domain.TrackingEventRecord;
import com.example.monkey.logistics.domain.TrackingStatus;
import com.example.monkey.shared.infrastructure.privacy.PiiCryptoService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class JpaLogisticsStoreTest {

    @Mock
    private LogisticsTrackingRepository trackingRepository;

    @Mock
    private LogisticsTrackingEventRepository trackingEventRepository;

    @Mock
    private FreightTemplateRepository freightTemplateRepository;

    @Mock
    private PiiCryptoService piiCryptoService;

    private JpaLogisticsStore store;

    @BeforeEach
    void setUp() {
        store = new JpaLogisticsStore(
                trackingRepository, trackingEventRepository, freightTemplateRepository, piiCryptoService);
    }

    @Test
    void saveTrackingEncryptsRecipientPhoneAndAddressBlindIndexes() {
        when(piiCryptoService.encrypt("13800138000")).thenReturn("encrypted-phone");
        when(piiCryptoService.blindIndex("13800138000")).thenReturn("phone-hmac");
        when(piiCryptoService.encrypt("Zhejiang Hangzhou Xihu Wenyi Road 100")).thenReturn("encrypted-address");
        when(piiCryptoService.blindIndex("Zhejiang Hangzhou Xihu Wenyi Road 100"))
                .thenReturn("address-hmac");
        when(trackingRepository.save(any(LogisticsTrackingEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        store.saveTracking(tracking());

        LogisticsTrackingEntity entity = captureTracking();
        assertThat(entity.getRecipientPhoneCiphertext()).isEqualTo("encrypted-phone");
        assertThat(entity.getRecipientPhoneHmac()).isEqualTo("phone-hmac");
        assertThat(entity.getAddressCiphertext()).isEqualTo("encrypted-address");
        assertThat(entity.getAddressHmac()).isEqualTo("address-hmac");
        assertThat(entity.getVersion()).isNull();
    }

    @Test
    void findTrackingMapsStoredEntityWithoutDecryptingPii() {
        when(trackingRepository.findByTrackingNo("SF7000")).thenReturn(Optional.of(trackingEntity()));

        LogisticsTracking found = store.findByTrackingNo("SF7000").orElseThrow();

        assertThat(found.trackingNo()).isEqualTo("SF7000");
        assertThat(found.recipientPhone()).isNull();
        assertThat(found.recipientPhoneBlindIndex()).isEqualTo("phone-hmac");
        assertThat(found.addressBlindIndex()).isEqualTo("address-hmac");
        assertThat(found.status()).isEqualTo(TrackingStatus.ORDERED);
    }

    @Test
    void saveEventAndFindEventsMapTimelineRecords() {
        when(trackingEventRepository.save(any(LogisticsTrackingEventEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(trackingEventRepository.findByTrackingNoOrderByEventTimeAsc("SF7000"))
                .thenReturn(List.of(eventEntity()));

        TrackingEventRecord saved = store.saveEvent(eventRecord());
        List<TrackingEventRecord> events = store.findEvents("SF7000");

        assertThat(saved.toStatus()).isEqualTo(TrackingStatus.PICKED_UP);
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.eventType()).isEqualTo(TrackingEvent.PICKUP);
            assertThat(event.location()).isEqualTo("Hangzhou hub");
        });
    }

    @Test
    void findFreightTemplatesIncludesWildcardAndRequestedProvince() {
        when(freightTemplateRepository.findByCarrierAndActiveTrueAndProvinceIn(
                        LogisticsCarrier.SF, List.of("*", "Zhejiang")))
                .thenReturn(List.of(
                        freightEntity("*", FreightChargeMode.WEIGHT),
                        freightEntity("Zhejiang", FreightChargeMode.REGION)));

        List<FreightTemplate> templates = store.findFreightTemplates(LogisticsCarrier.SF, "Zhejiang");

        assertThat(templates)
                .extracting(FreightTemplate::chargeMode)
                .containsExactly(FreightChargeMode.WEIGHT, FreightChargeMode.REGION);
    }

    private LogisticsTrackingEntity captureTracking() {
        ArgumentCaptor<LogisticsTrackingEntity> captor = ArgumentCaptor.forClass(LogisticsTrackingEntity.class);
        verify(trackingRepository).save(captor.capture());
        return captor.getValue();
    }

    private static LogisticsTracking tracking() {
        return new LogisticsTracking(
                7000L,
                "SF7000",
                10L,
                42L,
                LogisticsCarrier.SF,
                TrackingStatus.ORDERED,
                "13800138000",
                null,
                "Zhejiang Hangzhou Xihu Wenyi Road 100",
                null,
                "Zhejiang",
                "Hangzhou",
                "Xihu",
                "Zhejiang Hangzhou Xihu Wenyi Road 100",
                new BigDecimal("21.00"),
                24,
                "log-key",
                null,
                null,
                null,
                null,
                LocalDateTime.parse("2026-07-04T08:00:00"),
                LocalDateTime.parse("2026-07-04T08:00:00"));
    }

    private static LogisticsTrackingEntity trackingEntity() {
        LogisticsTrackingEntity entity = new LogisticsTrackingEntity();
        entity.setId(7000L);
        entity.setTrackingNo("SF7000");
        entity.setOrderId(10L);
        entity.setUserId(42L);
        entity.setCarrier(LogisticsCarrier.SF);
        entity.setStatus(TrackingStatus.ORDERED);
        entity.setRecipientPhoneCiphertext("encrypted-phone");
        entity.setRecipientPhoneHmac("phone-hmac");
        entity.setAddressCiphertext("encrypted-address");
        entity.setAddressHmac("address-hmac");
        entity.setProvince("Zhejiang");
        entity.setCity("Hangzhou");
        entity.setDistrict("Xihu");
        entity.setDetailSummary("Wenyi Road 100");
        entity.setFreightAmount(new BigDecimal("21.00"));
        entity.setEtaHours(24);
        entity.setIdempotencyKey("log-key");
        entity.setCreateTime(LocalDateTime.parse("2026-07-04T08:00:00"));
        entity.setUpdateTime(LocalDateTime.parse("2026-07-04T08:00:00"));
        entity.setVersion(1L);
        return entity;
    }

    private static TrackingEventRecord eventRecord() {
        return new TrackingEventRecord(
                7001L,
                7000L,
                "SF7000",
                LogisticsCarrier.SF,
                TrackingEvent.PICKUP,
                TrackingStatus.ORDERED,
                TrackingStatus.PICKED_UP,
                "event-1",
                LocalDateTime.parse("2026-07-04T09:00:00"),
                "Hangzhou hub",
                "picked up",
                LocalDateTime.parse("2026-07-04T09:00:00"));
    }

    private static LogisticsTrackingEventEntity eventEntity() {
        LogisticsTrackingEventEntity entity = new LogisticsTrackingEventEntity();
        entity.setId(7001L);
        entity.setTrackingId(7000L);
        entity.setTrackingNo("SF7000");
        entity.setCarrier(LogisticsCarrier.SF);
        entity.setEventType(TrackingEvent.PICKUP);
        entity.setFromStatus(TrackingStatus.ORDERED);
        entity.setToStatus(TrackingStatus.PICKED_UP);
        entity.setEventId("event-1");
        entity.setEventTime(LocalDateTime.parse("2026-07-04T09:00:00"));
        entity.setLocation("Hangzhou hub");
        entity.setRemark("picked up");
        entity.setCreateTime(LocalDateTime.parse("2026-07-04T09:00:00"));
        return entity;
    }

    private static FreightTemplateEntity freightEntity(String province, FreightChargeMode chargeMode) {
        FreightTemplateEntity entity = new FreightTemplateEntity();
        entity.setId(8000L);
        entity.setCarrier(LogisticsCarrier.SF);
        entity.setProvince(province);
        entity.setChargeMode(chargeMode);
        entity.setBaseWeightKg(BigDecimal.ONE);
        entity.setBaseFee(new BigDecimal("18.00"));
        entity.setStepWeightKg(BigDecimal.ONE);
        entity.setStepFee(new BigDecimal("6.00"));
        entity.setItemFee(new BigDecimal("3.00"));
        entity.setRegionFee(new BigDecimal("5.00"));
        entity.setEtaHours(24);
        entity.setActive(true);
        entity.setCreateTime(LocalDateTime.parse("2026-07-04T08:00:00"));
        entity.setUpdateTime(LocalDateTime.parse("2026-07-04T08:00:00"));
        return entity;
    }
}
