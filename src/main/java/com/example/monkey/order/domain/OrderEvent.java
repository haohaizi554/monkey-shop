package com.example.monkey.order.domain;

public enum OrderEvent {
    SHIP_PARTIAL,
    SHIP,
    RECEIVE_PARTIAL,
    RECEIVE,
    REQUEST_RETURN,
    APPROVE_RETURN,
    SHIP_RETURN,
    REFUND
}
