package com.example.monkey.user.application;

import static com.example.monkey.shared.application.security.AuthenticatedPrincipals.requireUserId;

import com.example.monkey.shared.application.dto.PageResponseDto;
import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.user.application.dto.AddressPageQuery;
import com.example.monkey.user.application.dto.AddressRequestDto;
import com.example.monkey.user.application.dto.AddressResponseDto;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class AddressApplicationService {

    private final AddressService addressService;

    public AddressApplicationService(AddressService addressService) {
        this.addressService = addressService;
    }

    public List<AddressResponseDto> findAddresses(SessionUser currentUser) {
        return addressService.findAddressesForUser(requireUserId(currentUser));
    }

    public PageResponseDto<AddressResponseDto> findAddresses(SessionUser currentUser, AddressPageQuery pageQuery) {
        return addressService.findAddressesForUser(requireUserId(currentUser), pageQuery);
    }

    public AddressResponseDto addAddress(SessionUser currentUser, AddressRequestDto request) {
        return addressService.addAddress(requireUserId(currentUser), request);
    }

    public AddressResponseDto updateAddress(SessionUser currentUser, Long id, AddressRequestDto request) {
        return addressService.updateAddress(requireUserId(currentUser), id, request);
    }

    public AddressResponseDto setDefault(SessionUser currentUser, Long id) {
        return addressService.setDefault(requireUserId(currentUser), id);
    }

    public void deleteAddress(SessionUser currentUser, Long id) {
        addressService.deleteAddress(requireUserId(currentUser), id);
    }
}
