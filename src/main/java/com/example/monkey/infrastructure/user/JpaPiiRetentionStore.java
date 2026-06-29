package com.example.monkey.infrastructure.user;

import com.example.monkey.domain.user.PiiRetentionStore;
import com.example.monkey.entity.Address;
import com.example.monkey.entity.Order;
import com.example.monkey.entity.User;
import com.example.monkey.repository.AddressRepository;
import com.example.monkey.repository.OrderRepository;
import com.example.monkey.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class JpaPiiRetentionStore implements PiiRetentionStore {

    private final UserRepository userRepository;
    private final AddressRepository addressRepository;
    private final OrderRepository orderRepository;

    public JpaPiiRetentionStore(
            UserRepository userRepository, AddressRepository addressRepository, OrderRepository orderRepository) {
        this.userRepository = userRepository;
        this.addressRepository = addressRepository;
        this.orderRepository = orderRepository;
    }

    @Override
    public boolean anonymizeUserProfile(Long userId) {
        return userRepository
                .findById(userId)
                .map(user -> {
                    scrubUser(user);
                    userRepository.save(user);
                    return true;
                })
                .orElse(false);
    }

    @Override
    public void anonymizeAddressesForUser(Long userId) {
        for (Address address : addressRepository.findByUserId(userId)) {
            scrubAddress(address);
            address.setDeleted(true);
            addressRepository.save(address);
        }
    }

    @Override
    public void anonymizeOrdersForUser(Long userId, String anonymizedBuyer) {
        for (Order order : orderRepository.findByUserId(userId)) {
            scrubOrder(order, anonymizedBuyer);
            orderRepository.save(order);
        }
    }

    @Override
    public int anonymizeOrdersCreatedBefore(List<String> statuses, LocalDateTime cutoff, String anonymizedBuyer) {
        List<Order> orders = orderRepository.findByStatusInAndCreateTimeBefore(statuses, cutoff);
        orders.forEach(order -> scrubOrder(order, anonymizedBuyer));
        orderRepository.saveAll(orders);
        return orders.size();
    }

    private static void scrubUser(User user) {
        user.setPhone(null);
        user.setEmail(null);
        user.setNickname(null);
    }

    private static void scrubAddress(Address address) {
        address.setReceiverName(null);
        address.setPhone(null);
        address.setPhoneHmac(null);
        address.setDetailAddress(null);
        address.setIsDefault(0);
    }

    private static void scrubOrder(Order order, String anonymizedBuyer) {
        order.setBuyerName(anonymizedBuyer);
        order.setReceiverName(null);
        order.setReceiverPhone(null);
        order.setReceiverPhoneHmac(null);
        order.setAddressSnapshot(null);
    }
}
