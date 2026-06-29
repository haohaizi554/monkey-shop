package com.example.monkey.domain.order;

import java.util.Arrays;

public enum OrderStatus {
    PAID("\u5df2\u652f\u4ed8"),
    SHIPPED("\u5df2\u53d1\u8d27"),
    COMPLETED("\u5df2\u5b8c\u6210"),
    RETURN_REQUESTED("\u7533\u8bf7\u9000\u8d27"),
    WAITING_RETURN_SHIPMENT("\u5f85\u9000\u8d27\u53d1\u8d27"),
    RETURN_SHIPPING("\u9000\u8d27\u4e2d"),
    REFUNDED("\u5df2\u9000\u6b3e");

    private final String label;

    OrderStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean matches(String storedValue) {
        return label.equals(storedValue) || name().equals(storedValue);
    }

    public static OrderStatus fromStoredValue(String storedValue) {
        return Arrays.stream(values())
                .filter(status -> status.matches(storedValue))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown order status: " + storedValue));
    }
}
