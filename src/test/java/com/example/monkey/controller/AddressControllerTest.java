package com.example.monkey.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.monkey.domain.user.AddressBook.AddressPageRequest;
import com.example.monkey.domain.user.AddressBook.SortOrder;
import com.example.monkey.domain.user.AddressBook.SortOrder.Direction;
import com.example.monkey.domain.user.SessionUser;
import com.example.monkey.domain.user.UserRoles;
import com.example.monkey.dto.AddressRequestDto;
import com.example.monkey.dto.AddressResponseDto;
import com.example.monkey.dto.PageResponseDto;
import com.example.monkey.service.AddressService;
import com.example.monkey.shared.api.ErrorCode;
import com.example.monkey.shared.api.Result;
import com.example.monkey.shared.exception.BusinessException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

@ExtendWith(MockitoExtension.class)
class AddressControllerTest {

    @Mock
    private AddressService addressService;

    private AddressController controller;

    @BeforeEach
    void setUp() {
        controller = new AddressController(addressService);
    }

    @Test
    void myAddressesUsesCurrentUserScope() {
        AddressResponseDto address = response();
        when(addressService.findAddressesForUser(7L)).thenReturn(List.of(address));

        Result<List<AddressResponseDto>> result = controller.myAddresses(user(7L));

        assertThat(result.code()).isEqualTo("OK");
        assertThat(result.data()).containsExactly(address);
        verify(addressService).findAddressesForUser(7L);
    }

    @Test
    void myAddressesPageUsesCurrentUserScope() {
        AddressResponseDto address = response();
        PageRequest pageable = PageRequest.of(0, 20, Sort.by(Sort.Order.asc("receiverName"), Sort.Order.desc("id")));
        PageResponseDto<AddressResponseDto> page = new PageResponseDto<>(List.of(address), 0, 20, 1, 1, true, true);
        when(addressService.findAddressesForUser(eq(7L), any(AddressPageRequest.class)))
                .thenReturn(page);

        Result<PageResponseDto<AddressResponseDto>> result = controller.myAddresses(pageable, user(7L));

        assertThat(result.code()).isEqualTo("OK");
        assertThat(result.data()).isSameAs(page);
        AddressPageRequest pageRequest = capturePageRequest();
        assertThat(pageRequest.page()).isZero();
        assertThat(pageRequest.size()).isEqualTo(20);
        assertThat(pageRequest.sortOrders())
                .containsExactly(new SortOrder("receiverName", Direction.ASC), new SortOrder("id", Direction.DESC));
    }

    @Test
    void myAddressesRejectsMissingAuthenticatedUser() {
        assertThatThrownBy(() -> controller.myAddresses(null))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));

        verifyNoInteractions(addressService);
    }

    @Test
    void addAddressDelegatesWithCurrentUserId() {
        AddressRequestDto request = request();
        AddressResponseDto address = response();
        when(addressService.addAddress(7L, request)).thenReturn(address);

        Result<AddressResponseDto> result = controller.addAddress(request, user(7L));

        assertThat(result.code()).isEqualTo("OK");
        assertThat(result.data()).isSameAs(address);
        verify(addressService).addAddress(7L, request);
    }

    @Test
    void addAddressRejectsMissingAuthenticatedUser() {
        assertThatThrownBy(() -> controller.addAddress(request(), null))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));

        verifyNoInteractions(addressService);
    }

    @Test
    void setDefaultDelegatesWithCurrentUserId() {
        AddressResponseDto address = response();
        when(addressService.setDefault(7L, 42L)).thenReturn(address);

        Result<AddressResponseDto> result = controller.setDefault(42L, user(7L));

        assertThat(result.code()).isEqualTo("OK");
        assertThat(result.data()).isSameAs(address);
        verify(addressService).setDefault(7L, 42L);
    }

    @Test
    void deleteDelegatesWithCurrentUserId() {
        Result<Void> result = controller.delete(42L, user(7L));

        assertThat(result.code()).isEqualTo("OK");
        assertThat(result.data()).isNull();
        verify(addressService).deleteAddress(7L, 42L);
    }

    private static AddressRequestDto request() {
        return new AddressRequestDto("Ada", "13800138000", "Hangzhou");
    }

    private static AddressResponseDto response() {
        return new AddressResponseDto(42L, "Ada", "13800138000", "Hangzhou", 1);
    }

    private AddressPageRequest capturePageRequest() {
        ArgumentCaptor<AddressPageRequest> captor = ArgumentCaptor.forClass(AddressPageRequest.class);
        verify(addressService).findAddressesForUser(eq(7L), captor.capture());
        return captor.getValue();
    }

    private static SessionUser user(Long id) {
        return new SessionUser(id, UserRoles.USER);
    }
}
