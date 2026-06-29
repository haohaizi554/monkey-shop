package com.example.monkey.infrastructure.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.domain.order.OrderStatus;
import com.example.monkey.entity.Address;
import com.example.monkey.entity.Order;
import com.example.monkey.entity.User;
import com.example.monkey.repository.AddressRepository;
import com.example.monkey.repository.OrderRepository;
import com.example.monkey.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class JpaPiiRetentionStoreTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private AddressRepository addressRepository;

    @Mock
    private OrderRepository orderRepository;

    @Test
    void anonymizesUserProfileWhenUserExists() {
        JpaPiiRetentionStore store = new JpaPiiRetentionStore(userRepository, addressRepository, orderRepository);
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
        JpaPiiRetentionStore store = new JpaPiiRetentionStore(userRepository, addressRepository, orderRepository);
        when(userRepository.findById(42L)).thenReturn(Optional.empty());

        boolean anonymized = store.anonymizeUserProfile(42L);

        assertThat(anonymized).isFalse();
    }

    @Test
    void anonymizesAndDeletesUserAddresses() {
        JpaPiiRetentionStore store = new JpaPiiRetentionStore(userRepository, addressRepository, orderRepository);
        Address address = new Address();
        address.setReceiverName("Buyer");
        address.setPhone("13800000000");
        address.setPhoneHmac("hash");
        address.setDetailAddress("Mountain");
        address.setIsDefault(1);
        when(addressRepository.findByUserId(42L)).thenReturn(List.of(address));

        store.anonymizeAddressesForUser(42L);

        assertThat(address.getReceiverName()).isNull();
        assertThat(address.getPhone()).isNull();
        assertThat(address.getPhoneHmac()).isNull();
        assertThat(address.getDetailAddress()).isNull();
        assertThat(address.getIsDefault()).isZero();
        assertThat(address.isDeleted()).isTrue();
        verify(addressRepository).save(address);
    }

    @Test
    void anonymizesOrdersForUser() {
        JpaPiiRetentionStore store = new JpaPiiRetentionStore(userRepository, addressRepository, orderRepository);
        Order order = orderWithPii();
        when(orderRepository.findByUserId(42L)).thenReturn(List.of(order));

        store.anonymizeOrdersForUser(42L, "anonymous");

        assertOrderPiiScrubbed(order);
        verify(orderRepository).save(order);
    }

    @Test
    void anonymizesFinalOrdersBeforeCutoff() {
        JpaPiiRetentionStore store = new JpaPiiRetentionStore(userRepository, addressRepository, orderRepository);
        Order order = orderWithPii();
        List<String> statuses = List.of(OrderStatus.COMPLETED.label(), OrderStatus.REFUNDED.label());
        LocalDateTime cutoff = LocalDateTime.parse("2025-12-28T00:00:00");
        when(orderRepository.findByStatusInAndCreateTimeBefore(statuses, cutoff))
                .thenReturn(List.of(order));

        int count = store.anonymizeOrdersCreatedBefore(statuses, cutoff, "anonymous");

        assertThat(count).isEqualTo(1);
        assertOrderPiiScrubbed(order);
        verify(orderRepository).saveAll(List.of(order));
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
    }
}
