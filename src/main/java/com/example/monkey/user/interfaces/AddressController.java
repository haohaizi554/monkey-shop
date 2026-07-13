package com.example.monkey.user.interfaces;

import com.example.monkey.shared.application.dto.PageResponseDto;
import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.interfaces.dto.Result;
import com.example.monkey.user.application.AddressApplicationService;
import com.example.monkey.user.application.dto.AddressPageQuery;
import com.example.monkey.user.application.dto.AddressPageQuery.SortOrder;
import com.example.monkey.user.application.dto.AddressPageQuery.SortOrder.Direction;
import com.example.monkey.user.application.dto.AddressRequestDto;
import com.example.monkey.user.application.dto.AddressResponseDto;
import jakarta.validation.Valid;
import java.util.List;
import org.springdoc.core.annotations.ParameterObject;
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

    private final AddressApplicationService addressApplicationService;

    public AddressController(AddressApplicationService addressApplicationService) {
        this.addressApplicationService = addressApplicationService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('ADDRESS_MANAGE')")
    public Result<PageResponseDto<AddressResponseDto>> myAddresses(
            @ParameterObject @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.ASC) Pageable pageable,
            @AuthenticationPrincipal SessionUser currentUser) {
        return Result.success(addressApplicationService.findAddresses(currentUser, toAddressPageQuery(pageable)));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ADDRESS_MANAGE')")
    public Result<AddressResponseDto> addAddress(
            @Valid @RequestBody AddressRequestDto request, @AuthenticationPrincipal SessionUser currentUser) {
        return Result.success(addressApplicationService.addAddress(currentUser, request));
    }

    @PostMapping("/set-default/{id}")
    @PreAuthorize("hasAuthority('ADDRESS_MANAGE')")
    public Result<AddressResponseDto> setDefault(
            @PathVariable Long id, @AuthenticationPrincipal SessionUser currentUser) {
        return Result.success(addressApplicationService.setDefault(currentUser, id));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('ADDRESS_MANAGE')")
    public Result<Void> delete(@PathVariable Long id, @AuthenticationPrincipal SessionUser currentUser) {
        addressApplicationService.deleteAddress(currentUser, id);
        return Result.success();
    }

    private static AddressPageQuery toAddressPageQuery(Pageable pageable) {
        List<SortOrder> sortOrders = pageable.getSort().stream()
                .map(order -> new SortOrder(order.getProperty(), order.isAscending() ? Direction.ASC : Direction.DESC))
                .toList();
        return new AddressPageQuery(pageable.getPageNumber(), pageable.getPageSize(), sortOrders);
    }
}
