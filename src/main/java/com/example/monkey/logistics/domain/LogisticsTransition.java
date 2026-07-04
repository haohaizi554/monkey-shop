package com.example.monkey.logistics.domain;

public record LogisticsTransition(TrackingStatus source, TrackingStatus target, TrackingEvent event) {}
