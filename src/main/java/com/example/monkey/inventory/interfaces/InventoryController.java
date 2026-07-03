package com.example.monkey.inventory.interfaces;

import com.example.monkey.inventory.application.InventoryApplicationService;
import com.example.monkey.inventory.application.dto.InventoryCompensateRequestDto;
import com.example.monkey.inventory.application.dto.InventoryReconciliationResponseDto;
import com.example.monkey.inventory.application.dto.InventoryReservationResponseDto;
import com.example.monkey.inventory.application.dto.InventoryReserveRequestDto;
import com.example.monkey.inventory.application.dto.WarehouseStockResponseDto;
import com.example.monkey.shared.interfaces.dto.Result;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/inventory", "/api/v1/inventory"})
public class InventoryController {

    private final InventoryApplicationService inventoryApplicationService;

    public InventoryController(InventoryApplicationService inventoryApplicationService) {
        this.inventoryApplicationService = inventoryApplicationService;
    }

    @GetMapping("/skus/{skuId}/stocks")
    @PreAuthorize("hasAnyAuthority('ORDER_CREATE', 'ORDER_MANAGE', 'PRODUCT_MANAGE')")
    public Result<List<WarehouseStockResponseDto>> stocks(@PathVariable Long skuId) {
        return Result.success(inventoryApplicationService.stocks(skuId));
    }

    @PostMapping("/reservations")
    @PreAuthorize("hasAuthority('ORDER_CREATE')")
    public Result<InventoryReservationResponseDto> reserve(@Valid @RequestBody InventoryReserveRequestDto request) {
        return Result.success(inventoryApplicationService.reserve(request));
    }

    @PostMapping("/reservations/{reservationKey}/release")
    @PreAuthorize("hasAnyAuthority('ORDER_CREATE', 'ORDER_MANAGE')")
    public Result<InventoryReservationResponseDto> release(@PathVariable String reservationKey) {
        return Result.success(inventoryApplicationService.release(reservationKey));
    }

    @PostMapping("/reservations/{reservationKey}/deduct")
    @PreAuthorize("hasAuthority('ORDER_MANAGE')")
    public Result<InventoryReservationResponseDto> deduct(@PathVariable String reservationKey) {
        return Result.success(inventoryApplicationService.deduct(reservationKey));
    }

    @PostMapping("/compensations")
    @PreAuthorize("hasAuthority('ORDER_MANAGE')")
    public Result<WarehouseStockResponseDto> compensate(@Valid @RequestBody InventoryCompensateRequestDto request) {
        return Result.success(inventoryApplicationService.compensate(request));
    }

    @GetMapping("/reconciliation")
    @PreAuthorize("hasAuthority('ORDER_MANAGE')")
    public Result<InventoryReconciliationResponseDto> reconcile() {
        return Result.success(inventoryApplicationService.reconcile());
    }
}
