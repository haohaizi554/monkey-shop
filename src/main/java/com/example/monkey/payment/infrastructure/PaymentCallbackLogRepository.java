package com.example.monkey.payment.infrastructure;

import com.example.monkey.payment.domain.PaymentMethod;
import com.example.monkey.shared.application.tenant.TenantContext;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentCallbackLogRepository extends JpaRepository<PaymentCallbackLogEntity, Long> {

    default int reserve(Long id, PaymentMethod provider, String paymentNo, String callbackId) {
        return reserve(TenantContext.currentTenantIdOrDefault(), id, provider.name(), paymentNo, callbackId);
    }

    @Modifying
    @Query(value = """
            INSERT IGNORE INTO payment_callback_log
                (tenant_id, id, provider, payment_no, callback_id, create_time)
            VALUES
                (:tenantId, :id, :provider, :paymentNo, :callbackId, CURRENT_TIMESTAMP(6))
            """, nativeQuery = true)
    int reserve(
            @Param("tenantId") Long tenantId,
            @Param("id") Long id,
            @Param("provider") String provider,
            @Param("paymentNo") String paymentNo,
            @Param("callbackId") String callbackId);

    default Optional<PaymentCallbackLogEntity> findByProviderAndCallbackId(PaymentMethod provider, String callbackId) {
        return findByTenantIdAndProviderAndCallbackId(TenantContext.currentTenantIdOrDefault(), provider, callbackId);
    }

    Optional<PaymentCallbackLogEntity> findByTenantIdAndProviderAndCallbackId(
            Long tenantId, PaymentMethod provider, String callbackId);
}
