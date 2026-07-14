package com.example.monkey.payment.infrastructure;

import com.example.monkey.payment.domain.PaymentMethod;
import com.example.monkey.payment.domain.PaymentOperationState;
import com.example.monkey.payment.domain.PaymentStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentOrderRepository extends JpaRepository<PaymentOrderEntity, Long> {

    Optional<PaymentOrderEntity> findByPaymentNo(String paymentNo);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select payment from PaymentOrderEntity payment where payment.paymentNo = :paymentNo")
    Optional<PaymentOrderEntity> findByPaymentNoForUpdate(@Param("paymentNo") String paymentNo);

    Optional<PaymentOrderEntity> findFirstByOrderIdAndUserIdOrderByCreateTimeDesc(Long orderId, Long userId);

    Optional<PaymentOrderEntity> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

    Optional<PaymentOrderEntity> findFirstByOrderIdAndStatusInOrderByCreateTimeDesc(
            Long orderId, Collection<PaymentStatus> statuses);

    List<PaymentOrderEntity> findByStatusAndOperationStateAndCreateTimeBeforeOrderByCreateTimeAsc(
            PaymentStatus status, PaymentOperationState operationState, LocalDateTime cutoff, Pageable pageable);

    List<PaymentOrderEntity> findByOperationStateInAndLeaseExpiresAtLessThanEqualOrderByLeaseExpiresAtAsc(
            Collection<PaymentOperationState> states, LocalDateTime cutoff, Pageable pageable);

    List<PaymentOrderEntity> findByMethodAndPaidAtGreaterThanEqualAndPaidAtLessThanAndStatusIn(
            PaymentMethod method, LocalDateTime start, LocalDateTime end, Collection<PaymentStatus> statuses);
}
