package com.example.monkey.product.domain;

public record PriceContext(String userIdentity, String region) {

    public boolean isMember() {
        return "MEMBER".equalsIgnoreCase(userIdentity)
                || "VIP".equalsIgnoreCase(userIdentity)
                || "PLUS".equalsIgnoreCase(userIdentity);
    }
}
