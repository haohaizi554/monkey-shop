package com.example.monkey.user.application;

import com.example.monkey.user.application.dto.AddressRequestDto;
import com.example.monkey.user.application.dto.AddressResponseDto;
import com.example.monkey.user.domain.AddressBook.AddressRecord;

public final class AddressDtoAssembler {

    private AddressDtoAssembler() {}

    public static AddressRecord toAddressRecord(Long userId, AddressRequestDto request, Integer isDefault) {
        return new AddressRecord(
                null, userId, request.receiverName(), request.phone(), request.detailAddress(), isDefault);
    }

    public static AddressResponseDto toResponse(AddressRecord address) {
        return new AddressResponseDto(
                address.id(), address.receiverName(), address.phone(), address.detailAddress(), address.isDefault());
    }
}
