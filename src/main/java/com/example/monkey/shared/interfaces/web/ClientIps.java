package com.example.monkey.shared.interfaces.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;

public final class ClientIps {

    private ClientIps() {}

    public static String resolve(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwardedFor)) {
            String firstForwardedValue = firstForwardedValue(forwardedFor);
            if (StringUtils.hasText(firstForwardedValue)) {
                return firstForwardedValue;
            }
        }
        String realIp = request.getHeader("X-Real-IP");
        if (StringUtils.hasText(realIp)) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }

    private static String firstForwardedValue(String forwardedFor) {
        return forwardedFor.split(",", 2)[0].trim();
    }
}
