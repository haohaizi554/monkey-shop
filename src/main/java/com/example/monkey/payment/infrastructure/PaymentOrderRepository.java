package com.example.monkey.payment.infrastructure;

import com.example.monkey.payment.domain.PaymentMethod;
import com.example.monkey.payment.domain.PaymentStatus;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentOrderRepository extends JpaRepository<PaymentOrderEntity, Long> {

    Optional<PaymentOrderEntity> findByPaymentNo(String paymentNo);

    Optional<PaymentOrderEntity> findFirstByOrderIdAndUserIdOrderByCreateTimeDesc(Long orderId, Long userId);

    Optional<PaymentOrderEntity> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

    List<PaymentOrderEntity> findByStatusAndCreateTimeBeforeOrderByCreateTimeAsc(
            PaymentStatus status, LocalDateTime cutoff, Pageable pageable);

    List<PaymentOrderEntity> findByMethodAndPaidAtGreaterThanEqualAndPaidAtLessThanAndStatusIn(
            PaymentMethod method, LocalDateTime start, LocalDateTime end, Collection<PaymentStatus> statuses);
}
