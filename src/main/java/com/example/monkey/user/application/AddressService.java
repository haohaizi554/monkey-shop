package com.example.monkey.user.application;

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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AddressService {

    private static final int LEGACY_LIST_PAGE_SIZE = 100;
    private static final AddressPageRequest LEGACY_LIST_REQUEST =
            new AddressPageRequest(0, LEGACY_LIST_PAGE_SIZE, List.of(new SortOrder("id", Direction.ASC)));

    private final AddressBook addressBook;

    public AddressService(AddressBook addressBook) {
        this.addressBook = addressBook;
    }

    @Transactional(readOnly = true)
    public List<AddressResponseDto> findAddressesForUser(Long userId) {
        return addressBook.findByUserId(userId, LEGACY_LIST_REQUEST).content().stream()
                .map(AddressDtoAssembler::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public PageResponseDto<AddressResponseDto> findAddressesForUser(Long userId, AddressPageQuery pageQuery) {
        AddressPage page = addressBook.findByUserId(userId, toAddressPageRequest(pageQuery));
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

    private static AddressPageRequest toAddressPageRequest(AddressPageQuery pageQuery) {
        List<SortOrder> sortOrders = pageQuery.sortOrders().stream()
                .map(AddressService::toDomainSortOrder)
                .toList();
        return new AddressPageRequest(pageQuery.page(), pageQuery.size(), sortOrders);
    }

    private static SortOrder toDomainSortOrder(AddressPageQuery.SortOrder sortOrder) {
        Direction direction =
                sortOrder.direction() == AddressPageQuery.SortOrder.Direction.DESC ? Direction.DESC : Direction.ASC;
        return new SortOrder(sortOrder.property(), direction);
    }
}
