package com.example.monkey.order.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.order.domain.OrderIdempotencyStore.IdempotencyReservationRecord;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class JpaOrderIdempotencyStoreTest {

    @Mock
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @Test
    void reservesIdempotencyRecordThroughRepositoryInsert() {
        JpaOrderIdempotencyStore store = new JpaOrderIdempotencyStore(idempotencyRecordRepository);
        LocalDateTime expiresAt = LocalDateTime.parse("2026-06-29T00:00:00");
        when(idempotencyRecordRepository.reserve(42L, "order-key-1", "request-hash", expiresAt))
                .thenReturn(1);

        boolean reserved = store.reserve(42L, "order-key-1", "request-hash", expiresAt);

        assertThat(reserved).isTrue();
        verify(idempotencyRecordRepository).reserve(42L, "order-key-1", "request-hash", expiresAt);
    }

    @Test
    void mapsExistingIdempotencyRecordToDomainRecord() {
        JpaOrderIdempotencyStore store = new JpaOrderIdempotencyStore(idempotencyRecordRepository);
        IdempotencyRecord entity = new IdempotencyRecord();
        entity.setId(5L);
        entity.setUserId(42L);
        entity.setIdempotencyKey("order-key-1");
        entity.setRequestHash("request-hash");
        entity.setOrderId(11L);
        entity.setStatus(IdempotencyRecord.STATUS_COMPLETED);
        entity.setCreatedAt(LocalDateTime.parse("2026-06-28T00:00:00"));
        entity.setExpiresAt(LocalDateTime.parse("2026-06-29T00:00:00"));
        when(idempotencyRecordRepository.findByUserIdAndIdempotencyKey(42L, "order-key-1"))
                .thenReturn(Optional.of(entity));

        Optional<IdempotencyReservationRecord> record = store.find(42L, "order-key-1");

        assertThat(record)
                .contains(new IdempotencyReservationRecord(
                        5L,
                        42L,
                        "order-key-1",
                        "request-hash",
                        11L,
                        IdempotencyRecord.STATUS_COMPLETED,
                        LocalDateTime.parse("2026-06-28T00:00:00"),
                        LocalDateTime.parse("2026-06-29T00:00:00")));
    }

    @Test
    void completesIdempotencyRecordThroughRepositoryUpdate() {
        JpaOrderIdempotencyStore store = new JpaOrderIdempotencyStore(idempotencyRecordRepository);

        store.complete(42L, "order-key-1", 11L);

        verify(idempotencyRecordRepository).complete(42L, "order-key-1", 11L);
    }
}
