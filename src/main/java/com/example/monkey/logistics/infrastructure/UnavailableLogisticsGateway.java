package com.example.monkey.logistics.infrastructure;

import com.example.monkey.logistics.domain.LogisticsGateway;
import com.example.monkey.logistics.domain.LogisticsGatewayResult;
import com.example.monkey.logistics.domain.LogisticsTracking;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.logistics.gateway", havingValue = "unavailable", matchIfMissing = false)
public final class UnavailableLogisticsGateway implements LogisticsGateway {

    static final String UNAVAILABLE_MESSAGE =
            "logistics gateway is unavailable; configure a real logistics adapter before creating shipments";

    @Override
    public LogisticsGatewayResult createShipment(LogisticsTracking tracking) {
        throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, UNAVAILABLE_MESSAGE);
    }
}
