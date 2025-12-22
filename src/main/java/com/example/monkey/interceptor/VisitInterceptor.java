package com.example.monkey.interceptor;

import com.example.monkey.entity.VisitLog;
import com.example.monkey.repository.VisitLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.LocalDateTime;

@Component
public class VisitInterceptor implements HandlerInterceptor {

    @Autowired
    private VisitLogRepository visitLogRepository;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 只记录 GET 请求，且排除 API 接口和静态资源（由 WebConfig 配置拦截路径）
        if ("GET".equalsIgnoreCase(request.getMethod())) {
            String ip = request.getRemoteAddr();
            visitLogRepository.save(new VisitLog(LocalDateTime.now(), ip));
        }
        return true;
    }
}