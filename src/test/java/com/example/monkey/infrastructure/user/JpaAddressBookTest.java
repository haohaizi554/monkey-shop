package com.example.monkey.infrastructure.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.domain.user.AddressBook.AddressPage;
import com.example.monkey.domain.user.AddressBook.AddressPageRequest;
import com.example.monkey.domain.user.AddressBook.AddressRecord;
import com.example.monkey.domain.user.AddressBook.SortOrder;
import com.example.monkey.domain.user.AddressBook.SortOrder.Direction;
import com.example.monkey.entity.Address;
import com.example.monkey.repository.AddressRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

@ExtendWith(MockitoExtension.class)
class JpaAddressBookTest {

    @Mock
    private AddressRepository addressRepository;

    private JpaAddressBook addressBook;

    @BeforeEach
    void setUp() {
        addressBook = new JpaAddressBook(addressRepository);
    }

    @Test
    void findByUserIdMapsRepositoryEntitiesToDomainRecords() {
        when(addressRepository.findByUserId(7L)).thenReturn(List.of(address()));

        List<AddressRecord> result = addressBook.findByUserId(7L);

        assertThat(result).containsExactly(record());
        verify(addressRepository).findByUserId(7L);
    }

    @Test
    void findPagedByUserIdMapsRepositoryPageAndPreservesSortOrders() {
        PageRequest repositoryPageable =
                PageRequest.of(0, 10, Sort.by(Sort.Order.asc("receiverName"), Sort.Order.desc("id")));
        when(addressRepository.findByUserId(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(address()), repositoryPageable, 1));

        AddressPage result = addressBook.findByUserId(
                7L,
                new AddressPageRequest(
                        0,
                        10,
                        List.of(new SortOrder("receiverName", Direction.ASC), new SortOrder("id", Direction.DESC))));

        assertThat(result.content()).containsExactly(record());
        assertThat(result.page()).isZero();
        assertThat(result.size()).isEqualTo(10);
        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.totalPages()).isEqualTo(1);
        assertThat(result.first()).isTrue();
        assertThat(result.last()).isTrue();

        Pageable pageable = capturePageable();
        assertThat(pageable.getSort().getOrderFor("receiverName").getDirection())
                .isEqualTo(Sort.Direction.ASC);
        assertThat(pageable.getSort().getOrderFor("id").getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void findPagedByUserIdUsesUnsortedPageableWhenNoSortOrdersAreProvided() {
        when(addressRepository.findByUserId(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 5), 0));

        AddressPage result = addressBook.findByUserId(7L, new AddressPageRequest(0, 5, null));

        assertThat(result.content()).isEmpty();
        Pageable pageable = capturePageable();
        assertThat(pageable.getSort().isUnsorted()).isTrue();
    }

    @Test
    void existsForUserDelegatesToRepository() {
        when(addressRepository.existsByUserId(7L)).thenReturn(true);

        assertThat(addressBook.existsForUser(7L)).isTrue();
    }

    @Test
    void findByIdAndUserIdMapsRepositoryOptional() {
        when(addressRepository.findByIdAndUserId(42L, 7L)).thenReturn(Optional.of(address()));

        Optional<AddressRecord> result = addressBook.findByIdAndUserId(42L, 7L);

        assertThat(result).contains(record());
    }

    @Test
    void saveMapsDomainRecordThroughRepositoryEntity() {
        when(addressRepository.save(any(Address.class))).thenReturn(address());

        AddressRecord result = addressBook.save(record());

        assertThat(result).isEqualTo(record());
        Address savedAddress = captureSavedAddress();
        assertThat(savedAddress.getId()).isEqualTo(42L);
        assertThat(savedAddress.getUserId()).isEqualTo(7L);
        assertThat(savedAddress.getReceiverName()).isEqualTo("Ada");
        assertThat(savedAddress.getIsDefault()).isEqualTo(1);
    }

    @Test
    void clearDefaultDelegatesToRepository() {
        addressBook.clearDefault(7L);

        verify(addressRepository).clearDefault(7L);
    }

    @Test
    void deleteByIdDelegatesToRepository() {
        addressBook.deleteById(42L);

        verify(addressRepository).deleteById(42L);
    }

    @Test
    void addressPageNormalizesNullContentToEmptyList() {
        AddressPage page = new AddressPage(null, 0, 5, 0, 0, true, true);

        assertThat(page.content()).isEmpty();
    }

    private Pageable capturePageable() {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(addressRepository).findByUserId(any(), captor.capture());
        return captor.getValue();
    }

    private Address captureSavedAddress() {
        ArgumentCaptor<Address> captor = ArgumentCaptor.forClass(Address.class);
        verify(addressRepository).save(captor.capture());
        return captor.getValue();
    }

    private static Address address() {
        Address address = new Address();
        address.setId(42L);
        address.setUserId(7L);
        address.setReceiverName("Ada");
        address.setPhone("13800138000");
        address.setDetailAddress("Hangzhou");
        address.setIsDefault(1);
        return address;
    }

    private static AddressRecord record() {
        return new AddressRecord(42L, 7L, "Ada", "13800138000", "Hangzhou", 1);
    }
}
