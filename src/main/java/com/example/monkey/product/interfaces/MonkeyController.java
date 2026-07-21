package com.example.monkey.product.interfaces;

import com.example.monkey.product.application.MonkeyService;
import com.example.monkey.product.application.dto.MonkeyRequestDto;
import com.example.monkey.product.application.dto.MonkeyResponseDto;
import com.example.monkey.product.application.dto.ProductPageQuery;
import com.example.monkey.product.application.dto.ProductPageQuery.SortOrder;
import com.example.monkey.product.application.dto.ProductPageQuery.SortOrder.Direction;
import com.example.monkey.product.interfaces.dto.MonkeyPageRequestDto;
import com.example.monkey.shared.application.dto.PageResponseDto;
import com.example.monkey.shared.interfaces.dto.Result;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.List;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
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
    public Result<PageResponseDto<MonkeyResponseDto>> getMonkeys(
            @ParameterObject @Valid @ModelAttribute MonkeyPageRequestDto request,
            @ParameterObject @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.ASC)
                    Pageable pageable) {
        return Result.success(monkeyService.findMonkeys(toProductPageQuery(
                pageable, request.keyword(), request.minPrice(), request.maxPrice(), request.inStock())));
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

    private static ProductPageQuery toProductPageQuery(
            Pageable pageable, String keyword, BigDecimal minPrice, BigDecimal maxPrice, Boolean inStock) {
        List<SortOrder> sortOrders = pageable.getSort().stream()
                .map(order -> new SortOrder(order.getProperty(), order.isAscending() ? Direction.ASC : Direction.DESC))
                .toList();
        return new ProductPageQuery(
                pageable.getPageNumber(), pageable.getPageSize(), sortOrders, keyword, minPrice, maxPrice, inStock);
    }
}
