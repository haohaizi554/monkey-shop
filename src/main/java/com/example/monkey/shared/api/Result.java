package com.example.monkey.shared.api;

import com.example.monkey.shared.web.TraceIds;

public record Result<T>(String code, String message, T data, String traceId) {

    public static <T> Result<T> success(T data) {
        return new Result<>("OK", "ok", data, TraceIds.currentOrCreate());
    }

    public static Result<Void> success() {
        return new Result<>("OK", "ok", null, TraceIds.currentOrCreate());
    }
}
