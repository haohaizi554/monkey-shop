package com.example.monkey.product.interfaces;

import com.example.monkey.product.application.ProductCatalogApplicationService;
import com.example.monkey.product.application.dto.CatalogCreateSpuRequestDto;
import com.example.monkey.product.application.dto.CatalogManagementPageQueryDto;
import com.example.monkey.product.application.dto.CatalogPriceQuoteDto;
import com.example.monkey.product.application.dto.CatalogPriceQuoteRequestDto;
import com.example.monkey.product.application.dto.CatalogSpuResponseDto;
import com.example.monkey.product.application.dto.CatalogStatusTransitionRequestDto;
import com.example.monkey.product.application.dto.CatalogUpdateSpuRequestDto;
import com.example.monkey.product.application.dto.CategoryNodeResponseDto;
import com.example.monkey.shared.application.dto.PageResponseDto;
import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.interfaces.dto.Result;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/catalog", "/api/v1/catalog"})
public class CatalogController {

    private final ProductCatalogApplicationService catalogApplicationService;

    public CatalogController(ProductCatalogApplicationService catalogApplicationService) {
        this.catalogApplicationService = catalogApplicationService;
    }

    @GetMapping({"/categories", "/categories/tree"})
    @PreAuthorize("permitAll()")
    public Result<List<CategoryNodeResponseDto>> categoryTree() {
        return Result.success(catalogApplicationService.categoryTree());
    }

    @GetMapping("/spus/{spuId}")
    @PreAuthorize("permitAll()")
    public Result<CatalogSpuResponseDto> getSpu(@PathVariable Long spuId) {
        return Result.success(catalogApplicationService.getSpu(spuId));
    }

    @GetMapping("/spus")
    @PreAuthorize("hasAuthority('PRODUCT_MANAGE')")
    public Result<PageResponseDto<CatalogSpuResponseDto>> managementList(
            @Valid @ModelAttribute CatalogManagementPageQueryDto request) {
        return Result.success(catalogApplicationService.findManagementPage(request));
    }

    @GetMapping("/spus/{spuId}/price")
    @PreAuthorize("permitAll()")
    public Result<CatalogPriceQuoteDto> quotePrice(
            @PathVariable Long spuId,
            @Valid @ModelAttribute CatalogPriceQuoteRequestDto request,
            @AuthenticationPrincipal SessionUser currentUser) {
        Long userId = currentUser == null ? null : currentUser.id();
        return Result.success(catalogApplicationService.quotePrice(spuId, userId, request.region()));
    }

    @PostMapping("/spus")
    @PreAuthorize("hasAuthority('PRODUCT_MANAGE')")
    public Result<CatalogSpuResponseDto> createSpu(@Valid @RequestBody CatalogCreateSpuRequestDto request) {
        return Result.success(catalogApplicationService.createSpu(request));
    }

    @PutMapping("/spus/{spuId}")
    @PreAuthorize("hasAuthority('PRODUCT_MANAGE')")
    public Result<CatalogSpuResponseDto> updateSpu(
            @PathVariable Long spuId, @Valid @RequestBody CatalogUpdateSpuRequestDto request) {
        return Result.success(catalogApplicationService.updateSpu(spuId, request));
    }

    @PostMapping("/spus/{spuId}/status")
    @PreAuthorize("hasAuthority('PRODUCT_MANAGE')")
    public Result<CatalogSpuResponseDto> transitionStatus(
            @PathVariable Long spuId, @Valid @RequestBody CatalogStatusTransitionRequestDto request) {
        return Result.success(catalogApplicationService.transitionStatus(spuId, request.targetStatus()));
    }

    @DeleteMapping("/spus/{spuId}")
    @PreAuthorize("hasAuthority('PRODUCT_MANAGE')")
    public Result<CatalogSpuResponseDto> retireSpu(@PathVariable Long spuId) {
        return Result.success(catalogApplicationService.retireSpu(spuId));
    }
}
