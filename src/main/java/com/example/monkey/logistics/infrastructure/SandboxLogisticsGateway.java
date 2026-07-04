package com.example.monkey.logistics.infrastructure;

import com.example.monkey.logistics.domain.LogisticsGateway;
import com.example.monkey.logistics.domain.LogisticsGatewayResult;
import com.example.monkey.logistics.domain.LogisticsTracking;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.logistics.gateway", havingValue = "sandbox", matchIfMissing = true)
public class SandboxLogisticsGateway implements LogisticsGateway {

    @Override
    public LogisticsGatewayResult createShipment(LogisticsTracking tracking) {
        return new LogisticsGatewayResult(
                tracking.carrier(),
                tracking.trackingNo(),
                tracking.status(),
                tracking.etaHours(),
                tracking.createTime());
    }
}
