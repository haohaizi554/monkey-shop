package com.example.monkey.user.interfaces;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.shared.application.dto.PageResponseDto;
import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.shared.interfaces.dto.Result;
import com.example.monkey.user.application.AddressApplicationService;
import com.example.monkey.user.application.dto.AddressPageQuery;
import com.example.monkey.user.application.dto.AddressPageQuery.SortOrder;
import com.example.monkey.user.application.dto.AddressPageQuery.SortOrder.Direction;
import com.example.monkey.user.application.dto.AddressRequestDto;
import com.example.monkey.user.application.dto.AddressResponseDto;
import com.example.monkey.user.domain.UserRoles;
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
    private AddressApplicationService addressApplicationService;

    private AddressController controller;

    @BeforeEach
    void setUp() {
        controller = new AddressController(addressApplicationService);
    }

    @Test
    void myAddressesDelegatesCurrentUserScope() {
        SessionUser currentUser = user(7L);
        AddressResponseDto address = response();
        when(addressApplicationService.findAddresses(currentUser)).thenReturn(List.of(address));

        Result<List<AddressResponseDto>> result = controller.myAddresses(currentUser);

        assertThat(result.code()).isEqualTo("OK");
        assertThat(result.data()).containsExactly(address);
        verify(addressApplicationService).findAddresses(currentUser);
    }

    @Test
    void myAddressesPageDelegatesCurrentUserScopeAndPageQuery() {
        SessionUser currentUser = user(7L);
        AddressResponseDto address = response();
        PageRequest pageable = PageRequest.of(0, 20, Sort.by(Sort.Order.asc("receiverName"), Sort.Order.desc("id")));
        PageResponseDto<AddressResponseDto> page = new PageResponseDto<>(List.of(address), 0, 20, 1, 1, true, true);
        when(addressApplicationService.findAddresses(eq(currentUser), any(AddressPageQuery.class)))
                .thenReturn(page);

        Result<PageResponseDto<AddressResponseDto>> result = controller.myAddresses(pageable, currentUser);

        assertThat(result.code()).isEqualTo("OK");
        assertThat(result.data()).isSameAs(page);
        AddressPageQuery pageRequest = capturePageRequest(currentUser);
        assertThat(pageRequest.page()).isZero();
        assertThat(pageRequest.size()).isEqualTo(20);
        assertThat(pageRequest.sortOrders())
                .containsExactly(new SortOrder("receiverName", Direction.ASC), new SortOrder("id", Direction.DESC));
    }

    @Test
    void myAddressesPropagatesMissingAuthenticatedUserFromApplicationService() {
        doThrow(new BusinessException(ErrorCode.UNAUTHORIZED, "login required"))
                .when(addressApplicationService)
                .findAddresses((SessionUser) null);

        assertThatThrownBy(() -> controller.myAddresses(null))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));

        verify(addressApplicationService).findAddresses((SessionUser) null);
    }

    @Test
    void addAddressDelegatesCurrentUserScope() {
        SessionUser currentUser = user(7L);
        AddressRequestDto request = request();
        AddressResponseDto address = response();
        when(addressApplicationService.addAddress(currentUser, request)).thenReturn(address);

        Result<AddressResponseDto> result = controller.addAddress(request, currentUser);

        assertThat(result.code()).isEqualTo("OK");
        assertThat(result.data()).isSameAs(address);
        verify(addressApplicationService).addAddress(currentUser, request);
    }

    @Test
    void addAddressPropagatesMissingAuthenticatedUserFromApplicationService() {
        AddressRequestDto request = request();
        doThrow(new BusinessException(ErrorCode.UNAUTHORIZED, "login required"))
                .when(addressApplicationService)
                .addAddress(null, request);

        assertThatThrownBy(() -> controller.addAddress(request, null))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));

        verify(addressApplicationService).addAddress(null, request);
    }

    @Test
    void setDefaultDelegatesCurrentUserScope() {
        SessionUser currentUser = user(7L);
        AddressResponseDto address = response();
        when(addressApplicationService.setDefault(currentUser, 42L)).thenReturn(address);

        Result<AddressResponseDto> result = controller.setDefault(42L, currentUser);

        assertThat(result.code()).isEqualTo("OK");
        assertThat(result.data()).isSameAs(address);
        verify(addressApplicationService).setDefault(currentUser, 42L);
    }

    @Test
    void deleteDelegatesCurrentUserScope() {
        SessionUser currentUser = user(7L);
        Result<Void> result = controller.delete(42L, currentUser);

        assertThat(result.code()).isEqualTo("OK");
        assertThat(result.data()).isNull();
        verify(addressApplicationService).deleteAddress(currentUser, 42L);
    }

    private static AddressRequestDto request() {
        return new AddressRequestDto("Ada", "13800138000", "Hangzhou");
    }

    private static AddressResponseDto response() {
        return new AddressResponseDto(42L, "Ada", "13800138000", "Hangzhou", 1);
    }

    private AddressPageQuery capturePageRequest(SessionUser currentUser) {
        ArgumentCaptor<AddressPageQuery> captor = ArgumentCaptor.forClass(AddressPageQuery.class);
        verify(addressApplicationService).findAddresses(eq(currentUser), captor.capture());
        return captor.getValue();
    }

    private static SessionUser user(Long id) {
        return new SessionUser(id, UserRoles.USER);
    }
}
