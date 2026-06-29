package com.example.monkey.infrastructure.user;

import com.example.monkey.domain.user.AddressBook;
import com.example.monkey.domain.user.AddressBook.AddressPage;
import com.example.monkey.domain.user.AddressBook.AddressPageRequest;
import com.example.monkey.domain.user.AddressBook.AddressRecord;
import com.example.monkey.domain.user.AddressBook.SortOrder.Direction;
import com.example.monkey.entity.Address;
import com.example.monkey.repository.AddressRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

@Component
public class JpaAddressBook implements AddressBook {

    private final AddressRepository addressRepository;

    public JpaAddressBook(AddressRepository addressRepository) {
        this.addressRepository = addressRepository;
    }

    @Override
    public List<AddressRecord> findByUserId(Long userId) {
        return addressRepository.findByUserId(userId).stream()
                .map(JpaAddressBook::toRecord)
                .toList();
    }

    @Override
    public AddressPage findByUserId(Long userId, AddressPageRequest request) {
        Page<AddressRecord> page =
                addressRepository.findByUserId(userId, toPageable(request)).map(JpaAddressBook::toRecord);
        return new AddressPage(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast());
    }

    @Override
    public boolean existsForUser(Long userId) {
        return addressRepository.existsByUserId(userId);
    }

    @Override
    public Optional<AddressRecord> findByIdAndUserId(Long id, Long userId) {
        return addressRepository.findByIdAndUserId(id, userId).map(JpaAddressBook::toRecord);
    }

    @Override
    public AddressRecord save(AddressRecord address) {
        return toRecord(addressRepository.save(toEntity(address)));
    }

    @Override
    public void clearDefault(Long userId) {
        addressRepository.clearDefault(userId);
    }

    @Override
    public void deleteById(Long id) {
        addressRepository.deleteById(id);
    }

    private static Pageable toPageable(AddressPageRequest request) {
        List<Sort.Order> orders = request.sortOrders().stream()
                .map(order -> new Sort.Order(toSpringDirection(order.direction()), order.property()))
                .toList();
        Sort sort = orders.isEmpty() ? Sort.unsorted() : Sort.by(orders);
        return PageRequest.of(request.page(), request.size(), sort);
    }

    private static Sort.Direction toSpringDirection(Direction direction) {
        return direction == Direction.DESC ? Sort.Direction.DESC : Sort.Direction.ASC;
    }

    private static AddressRecord toRecord(Address address) {
        return new AddressRecord(
                address.getId(),
                address.getUserId(),
                address.getReceiverName(),
                address.getPhone(),
                address.getDetailAddress(),
                address.getIsDefault());
    }

    private static Address toEntity(AddressRecord record) {
        Address address = new Address();
        address.setId(record.id());
        address.setUserId(record.userId());
        address.setReceiverName(record.receiverName());
        address.setPhone(record.phone());
        address.setDetailAddress(record.detailAddress());
        address.setIsDefault(record.isDefault());
        return address;
    }
}
