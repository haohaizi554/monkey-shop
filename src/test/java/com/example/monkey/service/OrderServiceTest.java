package com.example.monkey.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.entity.Address;
import com.example.monkey.entity.Monkey;
import com.example.monkey.entity.Order;
import com.example.monkey.entity.User;
import com.example.monkey.repository.AddressRepository;
import com.example.monkey.repository.MonkeyRepository;
import com.example.monkey.repository.OrderRepository;
import com.example.monkey.repository.UserRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private MonkeyRepository monkeyRepository;
    @Mock
    private AddressRepository addressRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private ImageCleanupService imageCleanupService;

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderService();
        ReflectionTestUtils.setField(orderService, "orderRepository", orderRepository);
        ReflectionTestUtils.setField(orderService, "monkeyRepository", monkeyRepository);
        ReflectionTestUtils.setField(orderService, "addressRepository", addressRepository);
        ReflectionTestUtils.setField(orderService, "userRepository", userRepository);
        ReflectionTestUtils.setField(orderService, "imageCleanupService", imageCleanupService);
    }

    @Test
    void createOrderSnapshotsProductIdAndGeneratesUuidBackedOrderNo() {
        Monkey monkey = new Monkey();
        monkey.setId(7L);
        monkey.setName("Momo");
        monkey.setImageUrl("/images/product/momo.png");
        monkey.setPrice(new BigDecimal("199.99"));
        monkey.setDescription("calm");

        Address address = new Address();
        address.setId(3L);
        address.setUserId(42L);
        address.setReceiverName("Ada");
        address.setPhone("13800000000");
        address.setDetailAddress("Hangzhou");

        User user = new User();
        user.setId(42L);
        user.setUsername("buyer");
        user.setAvatar("/images/avatar/buyer.png");

        when(monkeyRepository.findById(7L)).thenReturn(Optional.of(monkey));
        when(addressRepository.findById(3L)).thenReturn(Optional.of(address));
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));
        when(monkeyRepository.deductStock(7L)).thenReturn(1);

        String result = orderService.createOrder(42L, 7L, 3L);

        assertThat(result).isEqualTo("ok");
        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(captor.capture());
        Order saved = captor.getValue();
        assertThat(saved.getProductId()).isEqualTo(7L);
        assertThat(saved.getPrice()).isEqualByComparingTo("199.99");
        assertThat(saved.getOrderNo()).matches("ORD\\d{14}[0-9A-F]{32}");
    }

    @Test
    void updateStatusForOwnerRejectsAnotherUsersOrder() {
        Order order = new Order();
        order.setId(10L);
        order.setUserId(99L);
        order.setStatus("DONE");
        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));

        String result = orderService.updateStatusForOwner(10L, 42L, "RETURN_REQUESTED", "DONE");

        assertThat(result).startsWith("error:");
        assertThat(order.getStatus()).isEqualTo("DONE");
        verify(orderRepository, never()).save(order);
    }

    @Test
    void updateStatusForOwnerChangesOwnedOrderWhenStateMatches() {
        Order order = new Order();
        order.setId(10L);
        order.setUserId(42L);
        order.setStatus("DONE");
        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));

        String result = orderService.updateStatusForOwner(10L, 42L, "RETURN_REQUESTED", "DONE");

        assertThat(result).isEqualTo("ok");
        assertThat(order.getStatus()).isEqualTo("RETURN_REQUESTED");
        verify(orderRepository).save(order);
    }

    @Test
    void confirmReturnRestoresStockBySnapshotProductId() {
        Order order = new Order();
        order.setId(10L);
        order.setProductId(7L);
        order.setStatus("退货中");
        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));
        when(monkeyRepository.restoreStock(7L)).thenReturn(1);

        String result = orderService.confirmReturn(10L);

        assertThat(result).isEqualTo("ok");
        assertThat(order.getStatus()).isEqualTo("已退款");
        verify(monkeyRepository).restoreStock(7L);
        verify(orderRepository).save(order);
    }

    @Test
    void confirmReturnFailsClosedWhenProductIdSnapshotIsMissing() {
        Order order = new Order();
        order.setId(10L);
        order.setStatus("退货中");
        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));

        String result = orderService.confirmReturn(10L);

        assertThat(result).startsWith("error:");
        assertThat(order.getStatus()).isEqualTo("退货中");
        verify(monkeyRepository, never()).restoreStock(any());
        verify(orderRepository, never()).save(order);
    }
}
