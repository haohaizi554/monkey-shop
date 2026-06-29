package com.example.monkey.user.infrastructure;

import com.example.monkey.shared.infrastructure.persistence.JpaPageRequests;
import com.example.monkey.shared.infrastructure.persistence.JpaSorts;
import com.example.monkey.user.domain.AddressBook;
import com.example.monkey.user.domain.AddressBook.AddressPage;
import com.example.monkey.user.domain.AddressBook.AddressPageRequest;
import com.example.monkey.user.domain.AddressBook.AddressRecord;
import com.example.monkey.user.domain.AddressBook.SortOrder.Direction;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

@Component
public class JpaAddressBook implements AddressBook {

    private static final Set<String> ALLOWED_SORT_PROPERTIES = Set.of("id", "receiverName", "isDefault");

    private final AddressRepository addressRepository;

    public JpaAddressBook(AddressRepository addressRepository) {
        this.addressRepository = addressRepository;
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
                .flatMap(
                        order -> JpaSorts.allowedOrder(
                                order.property(), toSpringDirection(order.direction()), ALLOWED_SORT_PROPERTIES)
                                .stream())
                .toList();
        Sort sort = orders.isEmpty() ? Sort.unsorted() : Sort.by(orders);
        return JpaPageRequests.bounded(request.page(), request.size(), sort);
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
