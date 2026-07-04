package com.example.monkey.tenant.interfaces;

import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.interfaces.dto.Result;
import com.example.monkey.tenant.application.TenantApplicationService;
import com.example.monkey.tenant.application.dto.TenantBillDto;
import com.example.monkey.tenant.application.dto.TenantBillGenerateRequestDto;
import com.example.monkey.tenant.application.dto.TenantConfigDto;
import com.example.monkey.tenant.application.dto.TenantConfigRequestDto;
import com.example.monkey.tenant.application.dto.TenantCreateRequestDto;
import com.example.monkey.tenant.application.dto.TenantDashboardDto;
import com.example.monkey.tenant.application.dto.TenantDowngradeRequestDto;
import com.example.monkey.tenant.application.dto.TenantExportJobDto;
import com.example.monkey.tenant.application.dto.TenantExportRequestDto;
import com.example.monkey.tenant.application.dto.TenantRenewRequestDto;
import com.example.monkey.tenant.application.dto.TenantResponseDto;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/tenants", "/api/v1/tenants"})
public class TenantAdminController {

    private final TenantApplicationService tenantApplicationService;

    public TenantAdminController(TenantApplicationService tenantApplicationService) {
        this.tenantApplicationService = tenantApplicationService;
    }

    @GetMapping("/dashboard")
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    public Result<TenantDashboardDto> dashboard() {
        return Result.success(tenantApplicationService.dashboard());
    }

    @GetMapping
    @PreAuthorize("hasAuthority('TENANT_READ')")
    public Result<List<TenantResponseDto>> tenants() {
        return Result.success(tenantApplicationService.tenants());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    public Result<TenantResponseDto> createTenant(
            @AuthenticationPrincipal SessionUser currentUser, @Valid @RequestBody TenantCreateRequestDto request) {
        return Result.success(tenantApplicationService.createTenant(currentUser, request));
    }

    @PostMapping("/{tenantId}/renew")
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    public Result<TenantResponseDto> renewTenant(
            @AuthenticationPrincipal SessionUser currentUser,
            @PathVariable Long tenantId,
            @Valid @RequestBody(required = false) TenantRenewRequestDto request) {
        return Result.success(tenantApplicationService.renewTenant(currentUser, tenantId, request));
    }

    @PostMapping("/{tenantId}/downgrade")
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    public Result<TenantResponseDto> downgradeTenant(
            @AuthenticationPrincipal SessionUser currentUser,
            @PathVariable Long tenantId,
            @Valid @RequestBody(required = false) TenantDowngradeRequestDto request) {
        return Result.success(tenantApplicationService.downgradeTenant(currentUser, tenantId, request));
    }

    @GetMapping("/{tenantId}/configs")
    @PreAuthorize("hasAuthority('TENANT_READ')")
    public Result<List<TenantConfigDto>> configs(@PathVariable Long tenantId) {
        return Result.success(tenantApplicationService.configs(tenantId));
    }

    @PutMapping("/{tenantId}/configs")
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    public Result<TenantConfigDto> upsertConfig(
            @AuthenticationPrincipal SessionUser currentUser,
            @PathVariable Long tenantId,
            @Valid @RequestBody TenantConfigRequestDto request) {
        return Result.success(tenantApplicationService.upsertConfig(currentUser, tenantId, request));
    }

    @PostMapping("/{tenantId}/bills")
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    public Result<TenantBillDto> generateBill(
            @AuthenticationPrincipal SessionUser currentUser,
            @PathVariable Long tenantId,
            @Valid @RequestBody(required = false) TenantBillGenerateRequestDto request) {
        return Result.success(tenantApplicationService.generateBill(currentUser, tenantId, request));
    }

    @GetMapping("/{tenantId}/bills")
    @PreAuthorize("hasAuthority('TENANT_READ')")
    public Result<List<TenantBillDto>> bills(@PathVariable Long tenantId) {
        return Result.success(tenantApplicationService.bills(tenantId));
    }

    @PostMapping("/{tenantId}/exports")
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    public Result<TenantExportJobDto> requestExport(
            @AuthenticationPrincipal SessionUser currentUser,
            @PathVariable Long tenantId,
            @Valid @RequestBody(required = false) TenantExportRequestDto request) {
        return Result.success(tenantApplicationService.requestExport(currentUser, tenantId, request));
    }

    @GetMapping("/{tenantId}/exports")
    @PreAuthorize("hasAuthority('TENANT_READ')")
    public Result<List<TenantExportJobDto>> exports(@PathVariable Long tenantId) {
        return Result.success(tenantApplicationService.exports(tenantId));
    }
}
