package com.example.monkey.shared.domain.exception;

public enum ErrorCode {
    UNAUTHORIZED("UNAUTHORIZED", "Authentication is required"),
    FORBIDDEN("FORBIDDEN", "Operation is not permitted"),
    RATE_LIMIT("RATE_LIMIT", "Too many requests"),
    NOT_FOUND("NOT_FOUND", "Resource was not found"),
    VALIDATION_ERROR("VALIDATION_ERROR", "Validation failed"),
    CONFLICT("CONFLICT", "Resource state conflict"),
    OUT_OF_STOCK("OUT_OF_STOCK", "Insufficient stock"),
    SERVICE_UNAVAILABLE("SERVICE_UNAVAILABLE", "Service is temporarily unavailable"),
    INTERNAL_ERROR("INTERNAL_ERROR", "Internal server error");

    private final String code;
    private final String defaultMessage;

    ErrorCode(String code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public String code() {
        return code;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
