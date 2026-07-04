package com.example.monkey.logistics.domain;

import java.util.List;
import java.util.Optional;

public final class LogisticsTransitionPolicy {

    public static final String STATUS_TRANSITION_NOT_ALLOWED = "Logistics tracking status transition is not allowed";

    private static final List<LogisticsTransition> TRANSITIONS = List.of(
            new LogisticsTransition(TrackingStatus.ORDERED, TrackingStatus.PICKED_UP, TrackingEvent.PICKUP),
            new LogisticsTransition(TrackingStatus.PICKED_UP, TrackingStatus.IN_TRANSIT, TrackingEvent.TRANSIT),
            new LogisticsTransition(TrackingStatus.IN_TRANSIT, TrackingStatus.OUT_FOR_DELIVERY, TrackingEvent.DISPATCH),
            new LogisticsTransition(TrackingStatus.OUT_FOR_DELIVERY, TrackingStatus.SIGNED, TrackingEvent.SIGN));

    private LogisticsTransitionPolicy() {}

    public static Optional<TrackingStatus> nextStatus(TrackingStatus currentStatus, TrackingEvent event) {
        return TRANSITIONS.stream()
                .filter(transition -> transition.source() == currentStatus && transition.event() == event)
                .map(LogisticsTransition::target)
                .findFirst();
    }

    public static List<LogisticsTransition> transitions() {
        return TRANSITIONS;
    }
}
