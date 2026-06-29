package com.example.monkey.controller;

import static com.example.monkey.domain.user.AuthenticatedPrincipals.requireUserId;

import com.example.monkey.domain.user.AddressBook.AddressPageRequest;
import com.example.monkey.domain.user.AddressBook.SortOrder;
import com.example.monkey.domain.user.AddressBook.SortOrder.Direction;
import com.example.monkey.domain.user.SessionUser;
import com.example.monkey.dto.AddressRequestDto;
import com.example.monkey.dto.AddressResponseDto;
import com.example.monkey.dto.PageResponseDto;
import com.example.monkey.service.AddressService;
import com.example.monkey.shared.api.Result;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/address", "/api/v1/addresses"})
public class AddressController {

    private final AddressService addressService;

    public AddressController(AddressService addressService) {
        this.addressService = addressService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('ADDRESS_MANAGE')")
    public Result<List<AddressResponseDto>> myAddresses(@AuthenticationPrincipal SessionUser currentUser) {
        return Result.success(addressService.findAddressesForUser(requireUserId(currentUser)));
    }

    @GetMapping(params = {"page", "size"})
    @PreAuthorize("hasAuthority('ADDRESS_MANAGE')")
    public Result<PageResponseDto<AddressResponseDto>> myAddresses(
            @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.ASC) Pageable pageable,
            @AuthenticationPrincipal SessionUser currentUser) {
        return Result.success(
                addressService.findAddressesForUser(requireUserId(currentUser), toAddressPageRequest(pageable)));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ADDRESS_MANAGE')")
    public Result<AddressResponseDto> addAddress(
            @Valid @RequestBody AddressRequestDto request, @AuthenticationPrincipal SessionUser currentUser) {
        return Result.success(addressService.addAddress(requireUserId(currentUser), request));
    }

    @PostMapping("/set-default/{id}")
    @PreAuthorize("hasAuthority('ADDRESS_MANAGE')")
    public Result<AddressResponseDto> setDefault(
            @PathVariable Long id, @AuthenticationPrincipal SessionUser currentUser) {
        return Result.success(addressService.setDefault(requireUserId(currentUser), id));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('ADDRESS_MANAGE')")
    public Result<Void> delete(@PathVariable Long id, @AuthenticationPrincipal SessionUser currentUser) {
        addressService.deleteAddress(requireUserId(currentUser), id);
        return Result.success();
    }

    private static AddressPageRequest toAddressPageRequest(Pageable pageable) {
        List<SortOrder> sortOrders = pageable.getSort().stream()
                .map(order -> new SortOrder(order.getProperty(), order.isAscending() ? Direction.ASC : Direction.DESC))
                .toList();
        return new AddressPageRequest(pageable.getPageNumber(), pageable.getPageSize(), sortOrders);
    }
}
