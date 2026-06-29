package com.example.monkey.infrastructure.order;

import com.example.monkey.domain.order.OrderIdempotencyStore;
import com.example.monkey.domain.order.OrderIdempotencyStore.IdempotencyReservationRecord;
import com.example.monkey.entity.IdempotencyRecord;
import com.example.monkey.repository.IdempotencyRecordRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class JpaOrderIdempotencyStore implements OrderIdempotencyStore {

    private final IdempotencyRecordRepository idempotencyRecordRepository;

    public JpaOrderIdempotencyStore(IdempotencyRecordRepository idempotencyRecordRepository) {
        this.idempotencyRecordRepository = idempotencyRecordRepository;
    }

    @Override
    public boolean reserve(Long userId, String idempotencyKey, String requestHash, LocalDateTime expiresAt) {
        return idempotencyRecordRepository.reserve(userId, idempotencyKey, requestHash, expiresAt) == 1;
    }

    @Override
    public Optional<IdempotencyReservationRecord> find(Long userId, String idempotencyKey) {
        return idempotencyRecordRepository
                .findByUserIdAndIdempotencyKey(userId, idempotencyKey)
                .map(JpaOrderIdempotencyStore::toRecord);
    }

    @Override
    public void complete(Long userId, String idempotencyKey, Long orderId) {
        idempotencyRecordRepository.complete(userId, idempotencyKey, orderId);
    }

    private static IdempotencyReservationRecord toRecord(IdempotencyRecord record) {
        return new IdempotencyReservationRecord(
                record.getId(),
                record.getUserId(),
                record.getIdempotencyKey(),
                record.getRequestHash(),
                record.getOrderId(),
                record.getStatus(),
                record.getCreatedAt(),
                record.getExpiresAt());
    }
}
