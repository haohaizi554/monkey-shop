package com.example.monkey.risk.infrastructure;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.example.monkey.risk.application.RiskApplicationService;
import com.example.monkey.risk.application.dto.RiskAssessmentRequestDto;
import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.application.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RiskApplicationCommercialRiskGateTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void mapsAuthoritativeMarketingFactsAndHttpSignalsIntoRiskAssessment() {
        RiskApplicationService riskApplicationService = mock(RiskApplicationService.class);
        RiskApplicationCommercialRiskGate gate = new RiskApplicationCommercialRiskGate(riskApplicationService);
        TenantContext.setTenantId(41L);

        gate.requireAllowed(7L, 10L, 1001L, 88L, "device-7", "203.0.113.10", "marketing.seckill.order");

        verify(riskApplicationService).requireAllowed(
                eq(new SessionUser(7L, "USER", false, 41L)),
                eq(new RiskAssessmentRequestDto(
                        null,
                        "device-7",
                        null,
                        1001L,
                        88L,
                        10L,
                        null,
                        null,
                        null,
                        null)),
                eq("203.0.113.10"),
                eq("marketing.seckill.order"));
    }
}
