package com.example.monkey.membership.infrastructure;

import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MembershipCheckInRepository extends JpaRepository<MembershipCheckInEntity, Long> {

    Optional<MembershipCheckInEntity> findByUserIdAndCheckInDate(Long userId, LocalDate checkInDate);

    Optional<MembershipCheckInEntity> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

    Optional<MembershipCheckInEntity> findFirstByUserIdAndCheckInDateBeforeOrderByCheckInDateDesc(
            Long userId, LocalDate checkInDate);
}
