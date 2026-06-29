package com.example.monkey.controller;

import com.example.monkey.dto.AuditTraceEventDto;
import com.example.monkey.dto.AuditTraceRequestDto;
import com.example.monkey.dto.StatsQueryRequestDto;
import com.example.monkey.dto.StatsResponseDto;
import com.example.monkey.service.AuditService;
import com.example.monkey.service.StatsService;
import com.example.monkey.shared.api.Result;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/stats", "/api/v1/stats"})
public class StatsController {

    private final AuditService auditService;
    private final StatsService statsService;

    public StatsController(AuditService auditService, StatsService statsService) {
        this.auditService = auditService;
        this.statsService = statsService;
    }

    @GetMapping("/audit-trace")
    @PreAuthorize("hasAuthority('ADMIN_DASHBOARD_READ')")
    public Result<List<AuditTraceEventDto>> getAuditTrace(@Valid @ModelAttribute AuditTraceRequestDto request) {
        return Result.success(auditService.findByTraceId(request.traceId()));
    }

    @GetMapping("/data")
    @PreAuthorize("hasAuthority('ADMIN_DASHBOARD_READ')")
    public Result<StatsResponseDto> getStats(@Valid @ModelAttribute StatsQueryRequestDto request) {
        return Result.success(statsService.getStats(request.start(), request.end()));
    }
}
