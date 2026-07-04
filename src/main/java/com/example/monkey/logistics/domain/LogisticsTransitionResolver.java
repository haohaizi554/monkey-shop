package com.example.monkey.logistics.domain;

public interface LogisticsTransitionResolver {

    TrackingStatus nextStatus(TrackingStatus currentStatus, TrackingEvent event);
}
