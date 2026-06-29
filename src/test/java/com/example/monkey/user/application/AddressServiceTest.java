package com.example.monkey.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.shared.application.dto.PageResponseDto;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.user.application.dto.AddressPageQuery;
import com.example.monkey.user.application.dto.AddressRequestDto;
import com.example.monkey.user.application.dto.AddressResponseDto;
import com.example.monkey.user.domain.AddressBook;
import com.example.monkey.user.domain.AddressBook.AddressPage;
import com.example.monkey.user.domain.AddressBook.AddressPageRequest;
import com.example.monkey.user.domain.AddressBook.AddressRecord;
import com.example.monkey.user.domain.AddressBook.SortOrder;
import com.example.monkey.user.domain.AddressBook.SortOrder.Direction;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AddressServiceTest {

    @Mock
    private AddressBook addressBook;

    private AddressService addressService;

    @BeforeEach
    void setUp() {
        addressService = new AddressService(addressBook);
    }

    @Test
    void findAddressesForUserUsesBoundedLegacyPageRequest() {
        AddressRecord address = address();
        when(addressBook.findByUserId(eq(7L), any(AddressPageRequest.class)))
                .thenReturn(new AddressPage(List.of(address), 0, 100, 1, 1, true, true));

        List<AddressResponseDto> result = addressService.findAddressesForUser(7L);

        assertThat(result).containsExactly(response());
        AddressPageRequest pageRequest = capturePageRequest();
        assertThat(pageRequest.page()).isZero();
        assertThat(pageRequest.size()).isEqualTo(100);
        assertThat(pageRequest.sortOrders()).containsExactly(new SortOrder("id", Direction.ASC));
    }

    @Test
    void findAddressesForUserSupportsApplicationPageQueryContract() {
        AddressPageQuery pageQuery = new AddressPageQuery(
                0,
                10,
                List.of(
                        new AddressPageQuery.SortOrder("receiverName", AddressPageQuery.SortOrder.Direction.ASC),
                        new AddressPageQuery.SortOrder("id", AddressPageQuery.SortOrder.Direction.DESC)));
        AddressPage page = new AddressPage(List.of(address()), 0, 10, 1, 1, true, true);
        when(addressBook.findByUserId(eq(7L), any(AddressPageRequest.class))).thenReturn(page);

        PageResponseDto<AddressResponseDto> result = addressService.findAddressesForUser(7L, pageQuery);

        assertThat(result.content()).containsExactly(response());
        assertThat(result.page()).isZero();
        assertThat(result.size()).isEqualTo(10);
        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.totalPages()).isEqualTo(1);
        assertThat(result.first()).isTrue();
        assertThat(result.last()).isTrue();
        AddressPageRequest pageRequest = capturePageRequest();
        assertThat(pageRequest.page()).isZero();
        assertThat(pageRequest.size()).isEqualTo(10);
        assertThat(pageRequest.sortOrders())
                .containsExactly(new SortOrder("receiverName", Direction.ASC), new SortOrder("id", Direction.DESC));
    }

    @Test
    void addAddressStampsAuthenticatedUserId() {
        when(addressBook.existsForUser(7L)).thenReturn(false);

        AddressResponseDto result = addressService.addAddress(7L, request());

        assertThat(result.receiverName()).isEqualTo("Ada");
        assertThat(result.isDefault()).isEqualTo(1);
        AddressRecord savedAddress = captureSavedAddress();
        assertThat(savedAddress.userId()).isEqualTo(7L);
        assertThat(savedAddress.receiverName()).isEqualTo("Ada");
        assertThat(savedAddress.isDefault()).isEqualTo(1);
    }

    @Test
    void addAddressMarksSubsequentAddressesNonDefault() {
        when(addressBook.existsForUser(7L)).thenReturn(true);

        AddressResponseDto result = addressService.addAddress(7L, request());

        assertThat(result.isDefault()).isEqualTo(0);
        AddressRecord savedAddress = captureSavedAddress();
        assertThat(savedAddress.userId()).isEqualTo(7L);
        assertThat(savedAddress.isDefault()).isEqualTo(0);
    }

    @Test
    void setDefaultRejectsNonOwnedAddressBeforeClearingDefaults() {
        when(addressBook.findByIdAndUserId(42L, 7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> addressService.setDefault(7L, 42L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));

        verify(addressBook, never()).clearDefault(7L);
        verify(addressBook, never()).save(any());
    }

    @Test
    void setDefaultUpdatesOnlyOwnedAddress() {
        AddressRecord address = addressWithDefault(0);
        when(addressBook.findByIdAndUserId(42L, 7L)).thenReturn(Optional.of(address));

        AddressResponseDto result = addressService.setDefault(7L, 42L);

        assertThat(result.isDefault()).isEqualTo(1);
        verify(addressBook).clearDefault(7L);
        AddressRecord savedAddress = captureSavedAddress();
        assertThat(savedAddress.id()).isEqualTo(42L);
        assertThat(savedAddress.userId()).isEqualTo(7L);
        assertThat(savedAddress.isDefault()).isEqualTo(1);
    }

    @Test
    void deleteRejectsNonOwnedAddress() {
        when(addressBook.findByIdAndUserId(42L, 7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> addressService.deleteAddress(7L, 42L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));

        verify(addressBook, never()).deleteById(42L);
    }

    @Test
    void deleteRemovesOwnedAddress() {
        AddressRecord address = address();
        when(addressBook.findByIdAndUserId(42L, 7L)).thenReturn(Optional.of(address));

        addressService.deleteAddress(7L, 42L);

        verify(addressBook).deleteById(42L);
    }

    private AddressRecord captureSavedAddress() {
        ArgumentCaptor<AddressRecord> captor = ArgumentCaptor.forClass(AddressRecord.class);
        verify(addressBook).save(captor.capture());
        return captor.getValue();
    }

    private AddressPageRequest capturePageRequest() {
        ArgumentCaptor<AddressPageRequest> captor = ArgumentCaptor.forClass(AddressPageRequest.class);
        verify(addressBook).findByUserId(eq(7L), captor.capture());
        return captor.getValue();
    }

    private static AddressRequestDto request() {
        return new AddressRequestDto("Ada", "13800138000", "Hangzhou");
    }

    private static AddressRecord address() {
        return addressWithDefault(1);
    }

    private static AddressRecord addressWithDefault(Integer isDefault) {
        return new AddressRecord(42L, 7L, "Ada", "13800138000", "Hangzhou", isDefault);
    }

    private static AddressResponseDto response() {
        return new AddressResponseDto(42L, "Ada", "13800138000", "Hangzhou", 1);
    }
}
