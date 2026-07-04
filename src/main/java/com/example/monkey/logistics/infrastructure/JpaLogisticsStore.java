package com.example.monkey.logistics.infrastructure;

import com.example.monkey.logistics.domain.FreightTemplate;
import com.example.monkey.logistics.domain.LogisticsCarrier;
import com.example.monkey.logistics.domain.LogisticsStore;
import com.example.monkey.logistics.domain.LogisticsTracking;
import com.example.monkey.logistics.domain.TrackingEventRecord;
import com.example.monkey.shared.infrastructure.privacy.PiiCryptoService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@ConditionalOnProperty(name = "app.logistics.store", havingValue = "jpa", matchIfMissing = true)
public class JpaLogisticsStore implements LogisticsStore {

    private final LogisticsTrackingRepository trackingRepository;
    private final LogisticsTrackingEventRepository trackingEventRepository;
    private final FreightTemplateRepository freightTemplateRepository;
    private final PiiCryptoService piiCryptoService;

    public JpaLogisticsStore(
            LogisticsTrackingRepository trackingRepository,
            LogisticsTrackingEventRepository trackingEventRepository,
            FreightTemplateRepository freightTemplateRepository,
            PiiCryptoService piiCryptoService) {
        this.trackingRepository = trackingRepository;
        this.trackingEventRepository = trackingEventRepository;
        this.freightTemplateRepository = freightTemplateRepository;
        this.piiCryptoService = piiCryptoService;
    }

    @Override
    public Optional<LogisticsTracking> findByTrackingNo(String trackingNo) {
        return trackingRepository.findByTrackingNo(trackingNo).map(JpaLogisticsStore::toDomain);
    }

    @Override
    public Optional<LogisticsTracking> findByOrderIdAndUserId(Long orderId, Long userId) {
        return trackingRepository
                .findFirstByOrderIdAndUserIdOrderByCreateTimeDesc(orderId, userId)
                .map(JpaLogisticsStore::toDomain);
    }

    @Override
    public Optional<LogisticsTracking> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey) {
        return trackingRepository
                .findByUserIdAndIdempotencyKey(userId, idempotencyKey)
                .map(JpaLogisticsStore::toDomain);
    }

    @Override
    public LogisticsTracking saveTracking(LogisticsTracking tracking) {
        LogisticsTrackingEntity existing = tracking.id() == null
                ? null
                : trackingRepository.findById(tracking.id()).orElse(null);
        return toDomain(trackingRepository.save(toEntity(tracking, existing)));
    }

    @Override
    public TrackingEventRecord saveEvent(TrackingEventRecord event) {
        return toDomain(trackingEventRepository.save(toEntity(event)));
    }

    @Override
    public List<TrackingEventRecord> findEvents(String trackingNo) {
        return trackingEventRepository.findByTrackingNoOrderByEventTimeAsc(trackingNo).stream()
                .map(JpaLogisticsStore::toDomain)
                .toList();
    }

    @Override
    public List<FreightTemplate> findFreightTemplates(LogisticsCarrier carrier, String province) {
        List<String> provinces = new ArrayList<>();
        provinces.add("*");
        if (StringUtils.hasText(province)) {
            provinces.add(province.trim());
        }
        return freightTemplateRepository.findByCarrierAndActiveTrueAndProvinceIn(carrier, provinces).stream()
                .map(JpaLogisticsStore::toDomain)
                .toList();
    }

    private LogisticsTrackingEntity toEntity(LogisticsTracking tracking, LogisticsTrackingEntity existing) {
        LogisticsTrackingEntity entity = existing == null ? new LogisticsTrackingEntity() : existing;
        entity.setId(tracking.id());
        entity.setTrackingNo(tracking.trackingNo());
        entity.setOrderId(tracking.orderId());
        entity.setUserId(tracking.userId());
        entity.setCarrier(tracking.carrier());
        entity.setStatus(tracking.status());
        if (StringUtils.hasText(tracking.recipientPhone())) {
            entity.setRecipientPhoneCiphertext(piiCryptoService.encrypt(tracking.recipientPhone()));
            entity.setRecipientPhoneHmac(piiCryptoService.blindIndex(tracking.recipientPhone()));
        } else if (existing == null) {
            entity.setRecipientPhoneHmac(tracking.recipientPhoneBlindIndex());
        }
        if (StringUtils.hasText(tracking.addressSnapshot())) {
            entity.setAddressCiphertext(piiCryptoService.encrypt(tracking.addressSnapshot()));
            entity.setAddressHmac(piiCryptoService.blindIndex(tracking.addressSnapshot()));
        } else if (existing == null) {
            entity.setAddressHmac(tracking.addressBlindIndex());
        }
        entity.setProvince(tracking.province());
        entity.setCity(tracking.city());
        entity.setDistrict(tracking.district());
        entity.setDetailSummary(tracking.detailSummary());
        entity.setFreightAmount(tracking.freightAmount());
        entity.setEtaHours(tracking.etaHours());
        entity.setIdempotencyKey(tracking.idempotencyKey());
        entity.setPickedUpAt(tracking.pickedUpAt());
        entity.setInTransitAt(tracking.inTransitAt());
        entity.setOutForDeliveryAt(tracking.outForDeliveryAt());
        entity.setSignedAt(tracking.signedAt());
        entity.setCreateTime(tracking.createTime());
        entity.setUpdateTime(tracking.updateTime());
        return entity;
    }

    private static LogisticsTracking toDomain(LogisticsTrackingEntity entity) {
        return new LogisticsTracking(
                entity.getId(),
                entity.getTrackingNo(),
                entity.getOrderId(),
                entity.getUserId(),
                entity.getCarrier(),
                entity.getStatus(),
                null,
                entity.getRecipientPhoneHmac(),
                null,
                entity.getAddressHmac(),
                entity.getProvince(),
                entity.getCity(),
                entity.getDistrict(),
                entity.getDetailSummary(),
                entity.getFreightAmount(),
                entity.getEtaHours(),
                entity.getIdempotencyKey(),
                entity.getPickedUpAt(),
                entity.getInTransitAt(),
                entity.getOutForDeliveryAt(),
                entity.getSignedAt(),
                entity.getCreateTime(),
                entity.getUpdateTime());
    }

    private static LogisticsTrackingEventEntity toEntity(TrackingEventRecord event) {
        LogisticsTrackingEventEntity entity = new LogisticsTrackingEventEntity();
        entity.setId(event.id());
        entity.setTrackingId(event.trackingId());
        entity.setTrackingNo(event.trackingNo());
        entity.setCarrier(event.carrier());
        entity.setEventType(event.eventType());
        entity.setFromStatus(event.fromStatus());
        entity.setToStatus(event.toStatus());
        entity.setEventId(event.eventId());
        entity.setEventTime(event.eventTime());
        entity.setLocation(event.location());
        entity.setRemark(event.remark());
        entity.setCreateTime(event.createTime());
        return entity;
    }

    private static TrackingEventRecord toDomain(LogisticsTrackingEventEntity entity) {
        return new TrackingEventRecord(
                entity.getId(),
                entity.getTrackingId(),
                entity.getTrackingNo(),
                entity.getCarrier(),
                entity.getEventType(),
                entity.getFromStatus(),
                entity.getToStatus(),
                entity.getEventId(),
                entity.getEventTime(),
                entity.getLocation(),
                entity.getRemark(),
                entity.getCreateTime());
    }

    private static FreightTemplate toDomain(FreightTemplateEntity entity) {
        return new FreightTemplate(
                entity.getId(),
                entity.getCarrier(),
                entity.getProvince(),
                entity.getChargeMode(),
                entity.getBaseWeightKg(),
                entity.getBaseFee(),
                entity.getStepWeightKg(),
                entity.getStepFee(),
                entity.getItemFee(),
                entity.getRegionFee(),
                entity.getEtaHours(),
                Boolean.TRUE.equals(entity.getActive()));
    }
}
