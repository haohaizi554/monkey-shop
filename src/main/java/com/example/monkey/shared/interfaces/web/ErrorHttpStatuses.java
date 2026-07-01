package com.example.monkey.shared.interfaces.web;

import com.example.monkey.shared.domain.exception.ErrorCode;
import org.springframework.http.HttpStatus;

public final class ErrorHttpStatuses {

    private ErrorHttpStatuses() {}

    public static HttpStatus forCode(ErrorCode code) {
        return switch (code) {
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case RATE_LIMIT -> HttpStatus.TOO_MANY_REQUESTS;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case VALIDATION_ERROR -> HttpStatus.UNPROCESSABLE_ENTITY;
            case CONFLICT, OUT_OF_STOCK -> HttpStatus.CONFLICT;
            case SERVICE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
