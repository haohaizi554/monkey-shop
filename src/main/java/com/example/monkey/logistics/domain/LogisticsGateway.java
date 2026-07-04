package com.example.monkey.logistics.domain;

public interface LogisticsGateway {

    LogisticsGatewayResult createShipment(LogisticsTracking tracking);
}
