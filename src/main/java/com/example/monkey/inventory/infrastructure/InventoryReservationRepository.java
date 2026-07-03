package com.example.monkey.inventory.infrastructure;

import com.example.monkey.inventory.domain.InventoryReservationStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventoryReservationRepository extends JpaRepository<InventoryReservationEntity, Long> {

    Optional<InventoryReservationEntity> findByReservationKey(String reservationKey);

    boolean existsByReservationKey(String reservationKey);

    List<InventoryReservationEntity> findTop100ByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(
            InventoryReservationStatus status, LocalDateTime expiresAt);
}
