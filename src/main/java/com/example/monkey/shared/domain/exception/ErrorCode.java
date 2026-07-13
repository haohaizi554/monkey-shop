package com.example.monkey.shared.domain.exception;

public enum ErrorCode {
    REQUEST_MALFORMED("REQUEST_MALFORMED", "\u8bf7\u6c42\u683c\u5f0f\u65e0\u6cd5\u89e3\u6790"),
    VALIDATION_FAILED("VALIDATION_FAILED", "\u8bf7\u6c42\u53c2\u6570\u6821\u9a8c\u5931\u8d25"),
    UNAUTHORIZED("UNAUTHORIZED", "请登录后再试"),
    FORBIDDEN("FORBIDDEN", "当前账号没有权限执行这个操作"),
    RATE_LIMIT("RATE_LIMIT", "操作太频繁了，请稍后再试"),
    NOT_FOUND("NOT_FOUND", "资源不存在"),
    VALIDATION_ERROR("VALIDATION_ERROR", "请求参数校验失败"),
    CONFLICT("CONFLICT", "资源状态冲突"),
    OUT_OF_STOCK("OUT_OF_STOCK", "库存不足"),
    SERVICE_UNAVAILABLE("SERVICE_UNAVAILABLE", "服务暂时不可用"),
    INTERNAL_ERROR("INTERNAL_ERROR", "系统异常，请稍后再试");

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
