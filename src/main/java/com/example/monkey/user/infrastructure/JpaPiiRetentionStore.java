package com.example.monkey.user.infrastructure;

import com.example.monkey.order.infrastructure.Order;
import com.example.monkey.order.infrastructure.OrderRepository;
import com.example.monkey.user.domain.PiiRetentionStore;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
    public int anonymizeAddressesForUser(Long userId, int batchSize) {
        int anonymized = 0;
        PageRequest firstBatch = firstBatchById(batchSize);
        while (true) {
            List<Address> addresses = addressRepository
                    .findByUserIdAndDeletedFalse(userId, firstBatch)
                    .getContent();
            if (addresses.isEmpty()) {
                return anonymized;
            }
            addresses.forEach(address -> {
                scrubAddress(address);
                address.setDeleted(true);
            });
            addressRepository.saveAll(addresses);
            anonymized += addresses.size();
        }
    }

    @Override
    public int anonymizeOrdersForUser(Long userId, String anonymizedBuyer, int batchSize) {
        int anonymized = 0;
        PageRequest firstBatch = firstBatchById(batchSize);
        while (true) {
            List<Order> orders = orderRepository
                    .findByUserIdAndPiiAnonymizedFalse(userId, firstBatch)
                    .getContent();
            if (orders.isEmpty()) {
                return anonymized;
            }
            orders.forEach(order -> scrubOrder(order, anonymizedBuyer));
            orderRepository.saveAll(orders);
            anonymized += orders.size();
        }
    }

    @Override
    public int anonymizeOrdersCreatedBefore(
            List<String> statuses, LocalDateTime cutoff, String anonymizedBuyer, int batchSize) {
        PageRequest firstBatch = firstBatchByCreateTime(batchSize);
        List<Order> orders =
                orderRepository.findByStatusInAndCreateTimeBeforeAndPiiAnonymizedFalse(statuses, cutoff, firstBatch);
        orders.forEach(order -> scrubOrder(order, anonymizedBuyer));
        orderRepository.saveAll(orders);
        return orders.size();
    }

    private static PageRequest firstBatchById(int batchSize) {
        return PageRequest.of(0, Math.max(1, batchSize), Sort.by(Sort.Order.asc("id")));
    }

    private static PageRequest firstBatchByCreateTime(int batchSize) {
        return PageRequest.of(0, Math.max(1, batchSize), Sort.by(Sort.Order.asc("createTime"), Sort.Order.asc("id")));
    }

    private static void scrubUser(User user) {
        user.setPhone(null);
        user.setPhoneHmac(null);
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
        order.setPiiAnonymized(true);
    }
}
