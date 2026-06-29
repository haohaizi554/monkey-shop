package com.example.monkey.controller;

import com.example.monkey.domain.product.ProductCatalog.ProductPageRequest;
import com.example.monkey.domain.product.ProductCatalog.SortOrder;
import com.example.monkey.domain.product.ProductCatalog.SortOrder.Direction;
import com.example.monkey.dto.MonkeyRequestDto;
import com.example.monkey.dto.MonkeyResponseDto;
import com.example.monkey.dto.PageResponseDto;
import com.example.monkey.service.MonkeyService;
import com.example.monkey.shared.api.Result;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/monkeys", "/api/v1/monkeys"})
public class MonkeyController {

    private final MonkeyService monkeyService;

    public MonkeyController(MonkeyService monkeyService) {
        this.monkeyService = monkeyService;
    }

    @GetMapping
    @PreAuthorize("permitAll()")
    public Result<List<MonkeyResponseDto>> getAllMonkeys() {
        return Result.success(monkeyService.findAllMonkeys());
    }

    @GetMapping(params = {"page", "size"})
    @PreAuthorize("permitAll()")
    public Result<PageResponseDto<MonkeyResponseDto>> getMonkeys(
            @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.ASC) Pageable pageable) {
        return Result.success(monkeyService.findMonkeys(toProductPageRequest(pageable)));
    }

    @PostMapping("/add")
    @PreAuthorize("hasAuthority('PRODUCT_MANAGE')")
    public Result<MonkeyResponseDto> addMonkey(@Valid @RequestBody MonkeyRequestDto request) {
        return Result.success(monkeyService.addMonkey(request));
    }

    @PostMapping("/update")
    @PreAuthorize("hasAuthority('PRODUCT_MANAGE')")
    public Result<MonkeyResponseDto> updateMonkey(@Valid @RequestBody MonkeyRequestDto request) {
        return Result.success(monkeyService.updateMonkey(request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('PRODUCT_MANAGE')")
    public Result<Void> deleteMonkey(@PathVariable Long id) {
        monkeyService.deleteMonkey(id);
        return Result.success();
    }

    private static ProductPageRequest toProductPageRequest(Pageable pageable) {
        List<SortOrder> sortOrders = pageable.getSort().stream()
                .map(order -> new SortOrder(order.getProperty(), order.isAscending() ? Direction.ASC : Direction.DESC))
                .toList();
        return new ProductPageRequest(pageable.getPageNumber(), pageable.getPageSize(), sortOrders);
    }
}
