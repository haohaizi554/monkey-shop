package com.example.monkey.user.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.order.domain.OrderStatus;
import com.example.monkey.order.infrastructure.Order;
import com.example.monkey.order.infrastructure.OrderRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class JpaPiiRetentionStoreTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private AddressRepository addressRepository;

    @Mock
    private OrderRepository orderRepository;

    private JpaPiiRetentionStore store;

    @BeforeEach
    void setUp() {
        store = new JpaPiiRetentionStore(userRepository, addressRepository, orderRepository);
    }

    @Test
    void anonymizesUserProfileWhenUserExists() {
        User user = new User();
        user.setId(42L);
        user.setPhone("13800000000");
        user.setEmail("buyer@example.com");
        user.setNickname("buyer");
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));

        boolean anonymized = store.anonymizeUserProfile(42L);

        assertThat(anonymized).isTrue();
        assertThat(user.getPhone()).isNull();
        assertThat(user.getEmail()).isNull();
        assertThat(user.getNickname()).isNull();
        verify(userRepository).save(user);
    }

    @Test
    void reportsMissingUserWithoutSaving() {
        when(userRepository.findById(42L)).thenReturn(Optional.empty());

        boolean anonymized = store.anonymizeUserProfile(42L);

        assertThat(anonymized).isFalse();
    }

    @Test
    void anonymizesAndDeletesUserAddresses() {
        Address address = new Address();
        address.setReceiverName("Buyer");
        address.setPhone("13800000000");
        address.setPhoneHmac("hash");
        address.setDetailAddress("Mountain");
        address.setIsDefault(1);
        when(addressRepository.findByUserId(eq(42L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(address)), emptyAddressPage());

        int count = store.anonymizeAddressesForUser(42L, 250);

        assertThat(count).isEqualTo(1);
        assertThat(address.getReceiverName()).isNull();
        assertThat(address.getPhone()).isNull();
        assertThat(address.getPhoneHmac()).isNull();
        assertThat(address.getDetailAddress()).isNull();
        assertThat(address.getIsDefault()).isZero();
        assertThat(address.isDeleted()).isTrue();
        verify(addressRepository).saveAll(List.of(address));
        Pageable pageable = captureAddressPageable();
        assertThat(pageable.getPageNumber()).isZero();
        assertThat(pageable.getPageSize()).isEqualTo(250);
        assertThat(pageable.getSort().getOrderFor("id")).isNotNull();
    }

    @Test
    void anonymizesOrdersForUser() {
        Order order = orderWithPii();
        when(orderRepository.findByUserIdAndPiiAnonymizedFalse(eq(42L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(order)), emptyOrderPage());

        int count = store.anonymizeOrdersForUser(42L, "anonymous", 250);

        assertThat(count).isEqualTo(1);
        assertOrderPiiScrubbed(order);
        verify(orderRepository).saveAll(List.of(order));
        Pageable pageable = captureUserOrderPageable();
        assertThat(pageable.getPageNumber()).isZero();
        assertThat(pageable.getPageSize()).isEqualTo(250);
        assertThat(pageable.getSort().getOrderFor("id")).isNotNull();
    }

    @Test
    void forgetUserBatchesRepeatedlyFromFirstPageUntilEmpty() {
        Address firstAddress = addressWithPii();
        Address secondAddress = addressWithPii();
        Order firstOrder = orderWithPii();
        Order secondOrder = orderWithPii();
        when(addressRepository.findByUserId(eq(42L), any(Pageable.class)))
                .thenReturn(
                        new PageImpl<>(List.of(firstAddress)),
                        new PageImpl<>(List.of(secondAddress)),
                        emptyAddressPage());
        when(orderRepository.findByUserIdAndPiiAnonymizedFalse(eq(42L), any(Pageable.class)))
                .thenReturn(
                        new PageImpl<>(List.of(firstOrder)), new PageImpl<>(List.of(secondOrder)), emptyOrderPage());

        int addressCount = store.anonymizeAddressesForUser(42L, 1);
        int orderCount = store.anonymizeOrdersForUser(42L, "anonymous", 1);

        assertThat(addressCount).isEqualTo(2);
        assertThat(orderCount).isEqualTo(2);
        assertThat(firstAddress.isDeleted()).isTrue();
        assertThat(secondAddress.isDeleted()).isTrue();
        assertOrderPiiScrubbed(firstOrder);
        assertOrderPiiScrubbed(secondOrder);
        ArgumentCaptor<Pageable> addressPageable = ArgumentCaptor.forClass(Pageable.class);
        verify(addressRepository, times(3)).findByUserId(eq(42L), addressPageable.capture());
        assertThat(addressPageable.getAllValues()).allSatisfy(pageable -> {
            assertThat(pageable.getPageNumber()).isZero();
            assertThat(pageable.getPageSize()).isOne();
        });
        ArgumentCaptor<Pageable> orderPageable = ArgumentCaptor.forClass(Pageable.class);
        verify(orderRepository, times(3)).findByUserIdAndPiiAnonymizedFalse(eq(42L), orderPageable.capture());
        assertThat(orderPageable.getAllValues()).allSatisfy(pageable -> {
            assertThat(pageable.getPageNumber()).isZero();
            assertThat(pageable.getPageSize()).isOne();
        });
    }

    @Test
    void anonymizesFinalOrdersBeforeCutoffInBoundedBatch() {
        Order order = orderWithPii();
        List<String> statuses = List.of(OrderStatus.COMPLETED.label(), OrderStatus.REFUNDED.label());
        LocalDateTime cutoff = LocalDateTime.parse("2025-12-28T00:00:00");
        when(orderRepository.findByStatusInAndCreateTimeBeforeAndPiiAnonymizedFalse(
                        org.mockito.ArgumentMatchers.eq(statuses), org.mockito.ArgumentMatchers.eq(cutoff), any()))
                .thenReturn(List.of(order));

        int count = store.anonymizeOrdersCreatedBefore(statuses, cutoff, "anonymous", 250);

        assertThat(count).isEqualTo(1);
        assertOrderPiiScrubbed(order);
        Pageable pageable = captureRetentionPageable();
        assertThat(pageable.getPageNumber()).isZero();
        assertThat(pageable.getPageSize()).isEqualTo(250);
        assertThat(pageable.getSort().getOrderFor("createTime")).isNotNull();
        assertThat(pageable.getSort().getOrderFor("id")).isNotNull();
        verify(orderRepository).saveAll(List.of(order));
    }

    @Test
    void retentionBatchSizeIsClampedToOne() {
        List<String> statuses = List.of(OrderStatus.COMPLETED.label());
        LocalDateTime cutoff = LocalDateTime.parse("2025-12-28T00:00:00");
        when(orderRepository.findByStatusInAndCreateTimeBeforeAndPiiAnonymizedFalse(
                        org.mockito.ArgumentMatchers.eq(statuses), org.mockito.ArgumentMatchers.eq(cutoff), any()))
                .thenReturn(List.of());

        int count = store.anonymizeOrdersCreatedBefore(statuses, cutoff, "anonymous", 0);

        assertThat(count).isZero();
        assertThat(captureRetentionPageable().getPageSize()).isOne();
    }

    private Pageable captureRetentionPageable() {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(orderRepository)
                .findByStatusInAndCreateTimeBeforeAndPiiAnonymizedFalse(
                        any(), any(LocalDateTime.class), captor.capture());
        return captor.getValue();
    }

    private Pageable captureAddressPageable() {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(addressRepository, times(2)).findByUserId(eq(42L), captor.capture());
        return captor.getValue();
    }

    private Pageable captureUserOrderPageable() {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(orderRepository, times(2)).findByUserIdAndPiiAnonymizedFalse(eq(42L), captor.capture());
        return captor.getValue();
    }

    private static Address addressWithPii() {
        Address address = new Address();
        address.setReceiverName("Buyer");
        address.setPhone("13800000000");
        address.setPhoneHmac("hash");
        address.setDetailAddress("Mountain");
        address.setIsDefault(1);
        return address;
    }

    private static Page<Address> emptyAddressPage() {
        return new PageImpl<>(List.of());
    }

    private static Page<Order> emptyOrderPage() {
        return new PageImpl<>(List.of());
    }

    private static Order orderWithPii() {
        Order order = new Order();
        order.setBuyerName("buyer");
        order.setReceiverName("receiver");
        order.setReceiverPhone("13800000000");
        order.setReceiverPhoneHmac("hash");
        order.setAddressSnapshot("address");
        return order;
    }

    private static void assertOrderPiiScrubbed(Order order) {
        assertThat(order.getBuyerName()).isEqualTo("anonymous");
        assertThat(order.getReceiverName()).isNull();
        assertThat(order.getReceiverPhone()).isNull();
        assertThat(order.getReceiverPhoneHmac()).isNull();
        assertThat(order.getAddressSnapshot()).isNull();
        assertThat(order.isPiiAnonymized()).isTrue();
    }
}
