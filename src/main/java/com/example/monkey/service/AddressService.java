package com.example.monkey.service;

import com.example.monkey.assembler.AddressDtoAssembler;
import com.example.monkey.domain.user.AddressBook;
import com.example.monkey.domain.user.AddressBook.AddressPage;
import com.example.monkey.domain.user.AddressBook.AddressPageRequest;
import com.example.monkey.domain.user.AddressBook.AddressRecord;
import com.example.monkey.dto.AddressRequestDto;
import com.example.monkey.dto.AddressResponseDto;
import com.example.monkey.dto.PageResponseDto;
import com.example.monkey.shared.api.ErrorCode;
import com.example.monkey.shared.exception.BusinessException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AddressService {

    private final AddressBook addressBook;

    public AddressService(AddressBook addressBook) {
        this.addressBook = addressBook;
    }

    @Transactional(readOnly = true)
    public List<AddressResponseDto> findAddressesForUser(Long userId) {
        return addressBook.findByUserId(userId).stream()
                .map(AddressDtoAssembler::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public PageResponseDto<AddressResponseDto> findAddressesForUser(Long userId, AddressPageRequest pageRequest) {
        AddressPage page = addressBook.findByUserId(userId, pageRequest);
        return PageResponseDto.from(
                page.content().stream().map(AddressDtoAssembler::toResponse).toList(),
                page.page(),
                page.size(),
                page.totalElements(),
                page.totalPages(),
                page.first(),
                page.last());
    }

    @Transactional
    public AddressResponseDto addAddress(Long userId, AddressRequestDto request) {
        AddressRecord address =
                AddressDtoAssembler.toAddressRecord(userId, request, addressBook.existsForUser(userId) ? 0 : 1);
        AddressRecord savedAddress = addressBook.save(address);
        return AddressDtoAssembler.toResponse(savedAddress != null ? savedAddress : address);
    }

    @Transactional
    public AddressResponseDto setDefault(Long userId, Long id) {
        AddressRecord address = requireOwnedAddress(userId, id);
        addressBook.clearDefault(userId);
        AddressRecord defaultAddress = withDefault(address);
        AddressRecord savedAddress = addressBook.save(defaultAddress);
        return AddressDtoAssembler.toResponse(savedAddress != null ? savedAddress : defaultAddress);
    }

    @Transactional
    public void deleteAddress(Long userId, Long id) {
        requireOwnedAddress(userId, id);
        addressBook.deleteById(id);
    }

    private AddressRecord requireOwnedAddress(Long userId, Long id) {
        return addressBook
                .findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Address does not exist"));
    }

    private static AddressRecord withDefault(AddressRecord address) {
        return new AddressRecord(
                address.id(), address.userId(), address.receiverName(), address.phone(), address.detailAddress(), 1);
    }
}
