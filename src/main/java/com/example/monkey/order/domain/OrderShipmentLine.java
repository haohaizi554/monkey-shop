package com.example.monkey.order.domain;

public record OrderShipmentLine(Long id, Long shipmentId, Long orderId, Long skuId, String productName, int quantity) {}
