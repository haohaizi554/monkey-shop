package com.example.monkey.cart.interfaces;

import com.example.monkey.cart.application.CartApplicationService;
import com.example.monkey.cart.application.dto.CartAddItemRequestDto;
import com.example.monkey.cart.application.dto.CartCheckoutRequestDto;
import com.example.monkey.cart.application.dto.CartCheckoutResponseDto;
import com.example.monkey.cart.application.dto.CartResponseDto;
import com.example.monkey.cart.application.dto.CartSelectItemRequestDto;
import com.example.monkey.cart.application.dto.CartUpdateItemRequestDto;
import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.interfaces.dto.Result;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/cart", "/api/v1/cart"})
public class CartController {

    private final CartApplicationService cartApplicationService;

    public CartController(CartApplicationService cartApplicationService) {
        this.cartApplicationService = cartApplicationService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('ORDER_CREATE')")
    public Result<CartResponseDto> cart(@AuthenticationPrincipal SessionUser currentUser) {
        return Result.success(cartApplicationService.cart(currentUser));
    }

    @PostMapping("/items")
    @PreAuthorize("hasAuthority('ORDER_CREATE')")
    public Result<CartResponseDto> addItem(
            @Valid @RequestBody CartAddItemRequestDto request, @AuthenticationPrincipal SessionUser currentUser) {
        return Result.success(cartApplicationService.addItem(currentUser, request));
    }

    @PatchMapping("/items/{skuId}")
    @PreAuthorize("hasAuthority('ORDER_CREATE')")
    public Result<CartResponseDto> updateItem(
            @PathVariable Long skuId,
            @Valid @RequestBody CartUpdateItemRequestDto request,
            @AuthenticationPrincipal SessionUser currentUser) {
        return Result.success(cartApplicationService.updateItem(currentUser, skuId, request));
    }

    @PostMapping("/items/{skuId}/select")
    @PreAuthorize("hasAuthority('ORDER_CREATE')")
    public Result<CartResponseDto> selectItem(
            @PathVariable Long skuId,
            @Valid @RequestBody CartSelectItemRequestDto request,
            @AuthenticationPrincipal SessionUser currentUser) {
        return Result.success(cartApplicationService.selectItem(currentUser, skuId, request));
    }

    @DeleteMapping("/items/{skuId}")
    @PreAuthorize("hasAuthority('ORDER_CREATE')")
    public Result<CartResponseDto> removeItem(
            @PathVariable Long skuId, @AuthenticationPrincipal SessionUser currentUser) {
        return Result.success(cartApplicationService.removeItem(currentUser, skuId));
    }

    @PostMapping("/checkout/preview")
    @PreAuthorize("hasAuthority('ORDER_CREATE')")
    public Result<CartCheckoutResponseDto> previewCheckout(
            @Valid @RequestBody CartCheckoutRequestDto request, @AuthenticationPrincipal SessionUser currentUser) {
        return Result.success(cartApplicationService.previewCheckout(currentUser, request));
    }

    @PostMapping("/checkout")
    @PreAuthorize("hasAuthority('ORDER_CREATE')")
    public Result<CartCheckoutResponseDto> checkout(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CartCheckoutRequestDto request,
            @AuthenticationPrincipal SessionUser currentUser) {
        return Result.success(cartApplicationService.checkout(currentUser, request, idempotencyKey));
    }
}
