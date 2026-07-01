package com.example.monkey.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.monkey.shared.application.dto.PageResponseDto;
import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.user.application.dto.AddressPageQuery;
import com.example.monkey.user.application.dto.AddressRequestDto;
import com.example.monkey.user.application.dto.AddressResponseDto;
import com.example.monkey.user.domain.UserRoles;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AddressApplicationServiceTest {

    @Mock
    private AddressService addressService;

    private AddressApplicationService addressApplicationService;

    @BeforeEach
    void setUp() {
        addressApplicationService = new AddressApplicationService(addressService);
    }

    @Test
    void findAddressesRequiresAuthenticatedUserBeforeDelegating() {
        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> addressApplicationService.findAddresses(null))
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));

        verifyNoInteractions(addressService);
    }

    @Test
    void findAddressesDelegatesWithRequiredUserId() {
        SessionUser currentUser = user();
        AddressResponseDto address = response();
        when(addressService.findAddressesForUser(7L)).thenReturn(List.of(address));

        List<AddressResponseDto> result = addressApplicationService.findAddresses(currentUser);

        assertThat(result).containsExactly(address);
        verify(addressService).findAddressesForUser(7L);
    }

    @Test
    void findPagedAddressesDelegatesWithRequiredUserIdAndPageQuery() {
        SessionUser currentUser = user();
        AddressPageQuery pageQuery = new AddressPageQuery(0, 20, List.of());
        PageResponseDto<AddressResponseDto> page = new PageResponseDto<>(List.of(response()), 0, 20, 1, 1, true, true);
        when(addressService.findAddressesForUser(7L, pageQuery)).thenReturn(page);

        PageResponseDto<AddressResponseDto> result = addressApplicationService.findAddresses(currentUser, pageQuery);

        assertThat(result).isSameAs(page);
        verify(addressService).findAddressesForUser(7L, pageQuery);
    }

    @Test
    void addAddressDelegatesWithRequiredUserId() {
        SessionUser currentUser = user();
        AddressRequestDto request = request();
        AddressResponseDto address = response();
        when(addressService.addAddress(7L, request)).thenReturn(address);

        AddressResponseDto result = addressApplicationService.addAddress(currentUser, request);

        assertThat(result).isSameAs(address);
        verify(addressService).addAddress(7L, request);
    }

    @Test
    void setDefaultDelegatesWithRequiredUserId() {
        SessionUser currentUser = user();
        AddressResponseDto address = response();
        when(addressService.setDefault(7L, 42L)).thenReturn(address);

        AddressResponseDto result = addressApplicationService.setDefault(currentUser, 42L);

        assertThat(result).isSameAs(address);
        verify(addressService).setDefault(7L, 42L);
    }

    @Test
    void deleteAddressDelegatesWithRequiredUserId() {
        SessionUser currentUser = user();

        addressApplicationService.deleteAddress(currentUser, 42L);

        verify(addressService).deleteAddress(7L, 42L);
    }

    private static SessionUser user() {
        return new SessionUser(7L, UserRoles.USER);
    }

    private static AddressRequestDto request() {
        return new AddressRequestDto("Ada", "13800138000", "Hangzhou");
    }

    private static AddressResponseDto response() {
        return new AddressResponseDto(42L, "Ada", "13800138000", "Hangzhou", 1);
    }
}
