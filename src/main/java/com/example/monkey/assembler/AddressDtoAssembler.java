package com.example.monkey.assembler;

import com.example.monkey.domain.user.AddressBook.AddressRecord;
import com.example.monkey.dto.AddressRequestDto;
import com.example.monkey.dto.AddressResponseDto;
import com.example.monkey.entity.Address;

public final class AddressDtoAssembler {

    private AddressDtoAssembler() {}

    public static Address toEntity(AddressRequestDto request) {
        Address address = new Address();
        address.setReceiverName(request.receiverName());
        address.setPhone(request.phone());
        address.setDetailAddress(request.detailAddress());
        return address;
    }

    public static AddressRecord toAddressRecord(Long userId, AddressRequestDto request, Integer isDefault) {
        return new AddressRecord(
                null, userId, request.receiverName(), request.phone(), request.detailAddress(), isDefault);
    }

    public static AddressResponseDto toResponse(Address address) {
        return new AddressResponseDto(
                address.getId(),
                address.getReceiverName(),
                address.getPhone(),
                address.getDetailAddress(),
                address.getIsDefault());
    }

    public static AddressResponseDto toResponse(AddressRecord address) {
        return new AddressResponseDto(
                address.id(), address.receiverName(), address.phone(), address.detailAddress(), address.isDefault());
    }
}
