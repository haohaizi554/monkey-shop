package com.example.monkey.shared.api;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    UNAUTHORIZED("UNAUTHORIZED", HttpStatus.UNAUTHORIZED, "Authentication is required"),
    FORBIDDEN("FORBIDDEN", HttpStatus.FORBIDDEN, "Operation is not permitted"),
    RATE_LIMIT("RATE_LIMIT", HttpStatus.TOO_MANY_REQUESTS, "Too many requests"),
    NOT_FOUND("NOT_FOUND", HttpStatus.NOT_FOUND, "Resource was not found"),
    VALIDATION_ERROR("VALIDATION_ERROR", HttpStatus.UNPROCESSABLE_ENTITY, "Validation failed"),
    CONFLICT("CONFLICT", HttpStatus.CONFLICT, "Resource state conflict"),
    OUT_OF_STOCK("OUT_OF_STOCK", HttpStatus.CONFLICT, "Insufficient stock"),
    SERVICE_UNAVAILABLE("SERVICE_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE, "Service is temporarily unavailable"),
    INTERNAL_ERROR("INTERNAL_ERROR", HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");

    private final String code;
    private final HttpStatus httpStatus;
    private final String defaultMessage;

    ErrorCode(String code, HttpStatus httpStatus, String defaultMessage) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public String code() {
        return code;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
