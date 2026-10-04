package com.example.monkey.risk.infrastructure;

import com.example.monkey.risk.application.RiskApplicationService;
import com.example.monkey.risk.application.dto.RiskAssessmentRequestDto;
import com.example.monkey.risk.domain.CommercialRiskGate;
import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.application.tenant.TenantContext;
import org.springframework.stereotype.Component;

@Component
public class RiskApplicationCommercialRiskGate implements CommercialRiskGate {

    private final RiskApplicationService riskApplicationService;

    public RiskApplicationCommercialRiskGate(RiskApplicationService riskApplicationService) {
        this.riskApplicationService = riskApplicationService;
    }

    @Override
    public void requireAllowed(
            Long userId,
            Long activityId,
            Long productId,
            Long orderId,
            String deviceFingerprint,
            String clientIp,
            String operation) {
        requireAllowed(
                userId, activityId, productId, orderId, deviceFingerprint, clientIp, operation, null);
    }

    @Override
    public void requireAllowed(
            Long userId,
            Long activityId,
            Long productId,
            Long orderId,
            String deviceFingerprint,
            String clientIp,
            String operation,
            String totpCode) {
        riskApplicationService.requireAllowed(
                new SessionUser(userId, "USER", false, TenantContext.currentTenantIdOrDefault()),
                new RiskAssessmentRequestDto(
                        null,
                        deviceFingerprint,
                        null,
                        productId,
                        orderId,
                        activityId,
                        null,
                        null,
                        null,
                        totpCode),
                clientIp,
                operation);
    }
}
