package com.example.monkey.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.order.domain.CheckoutOrderCommand;
import com.example.monkey.order.domain.CheckoutOrderCommand.Line;
import com.example.monkey.order.domain.CheckoutOrderCommand.SubOrder;
import com.example.monkey.order.domain.OrderCustomerPort;
import com.example.monkey.order.domain.OrderStatus;
import com.example.monkey.order.domain.OrderStore;
import com.example.monkey.order.domain.OrderStore.AddressRecord;
import com.example.monkey.order.domain.OrderStore.BuyerRecord;
import com.example.monkey.order.domain.OrderStore.CheckoutOrderRecord;
import com.example.monkey.order.domain.OrderStore.OrderRecord;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CheckoutOrderApplicationServiceTest {

    private static final long CHECKOUT_ID = 701L;
    private static final long USER_ID = 7L;
    private static final long ADDRESS_ID = 9L;

    @Mock
    private OrderStore orderStore;

    @Mock
    private OrderCustomerPort customerPort;

    @Captor
    private ArgumentCaptor<List<CheckoutOrderRecord>> ordersCaptor;

    private CheckoutOrderApplicationService service;

    @BeforeEach
    void setUp() {
        service = new CheckoutOrderApplicationService(orderStore, customerPort);
    }

    @Test
    void createsOnePendingPaymentOrderPerShopAndConservesPayableAmount() {
        CheckoutOrderCommand command = command();
        when(orderStore.findByCheckoutId(CHECKOUT_ID)).thenReturn(List.of());
        when(customerPort.findBuyerById(USER_ID))
                .thenReturn(java.util.Optional.of(new BuyerRecord(USER_ID, "momo", "/avatar.png")));
        when(customerPort.findAddressById(ADDRESS_ID))
                .thenReturn(java.util.Optional.of(
                        new AddressRecord(ADDRESS_ID, USER_ID, "Momo", "13800000000", "Beijing")));
        AtomicLong ids = new AtomicLong(900L);
        when(orderStore.saveCheckoutOrders(any()))
                .thenAnswer(invocation -> invocation.<List<CheckoutOrderRecord>>getArgument(0).stream()
                        .map(snapshot -> snapshot.order().withId(ids.incrementAndGet()))
                        .toList());

        List<Long> orderIds = service.create(command);

        assertThat(orderIds).containsExactly(901L, 902L);
        verify(orderStore).saveCheckoutOrders(ordersCaptor.capture());
        List<CheckoutOrderRecord> savedOrders = ordersCaptor.getValue();
        assertThat(savedOrders).hasSize(2).allSatisfy(snapshot -> {
            assertThat(snapshot.order().checkoutId()).isEqualTo(CHECKOUT_ID);
            assertThat(snapshot.order().status()).isEqualTo(OrderStatus.PENDING_PAYMENT.label());
            assertThat(snapshot.lines()).isNotEmpty();
        });
        assertThat(savedOrders.stream()
                        .map(snapshot -> snapshot.order().price())
                        .reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(command.totalPayable());
    }

    @Test
    void returnsExistingOrdersForAnIdempotentCheckoutRetry() {
        CheckoutOrderCommand command = command();
        when(orderStore.findByCheckoutId(CHECKOUT_ID))
                .thenReturn(List.of(
                        existingOrder(901L, 801L, 11L, "200.00", "20.00", "180.00"),
                        existingOrder(902L, 802L, 12L, "100.00", "20.00", "80.00")));

        List<Long> orderIds = service.create(command);

        assertThat(orderIds).containsExactly(901L, 902L);
        verify(orderStore, never()).saveCheckoutOrders(any());
    }

    private static CheckoutOrderCommand command() {
        return new CheckoutOrderCommand(
                CHECKOUT_ID,
                USER_ID,
                ADDRESS_ID,
                "checkout-key-1",
                List.of(
                        new SubOrder(
                                801L,
                                11L,
                                "ORD-801",
                                new BigDecimal("200.00"),
                                new BigDecimal("15.00"),
                                new BigDecimal("5.00"),
                                new BigDecimal("180.00"),
                                List.of(line(8101L, 1001L, 11L, "Phone", "200.00", "20.00", "180.00"))),
                        new SubOrder(
                                802L,
                                12L,
                                "ORD-802",
                                new BigDecimal("100.00"),
                                BigDecimal.ZERO,
                                new BigDecimal("20.00"),
                                new BigDecimal("80.00"),
                                List.of(line(8102L, 1002L, 12L, "Keyboard", "100.00", "20.00", "80.00")))));
    }

    private static Line line(
            Long checkoutLineId,
            Long skuId,
            Long shopId,
            String productName,
            String originalAmount,
            String discountAmount,
            String payableAmount) {
        return new Line(
                checkoutLineId,
                skuId,
                shopId,
                501L,
                productName,
                "/product.png",
                1,
                new BigDecimal(originalAmount),
                new BigDecimal(originalAmount),
                new BigDecimal(discountAmount),
                new BigDecimal(payableAmount),
                List.of("PLATFORM-20"),
                "reservation-" + checkoutLineId,
                91L);
    }

    private static OrderRecord existingOrder(
            Long id, Long subOrderId, Long shopId, String originalAmount, String discountAmount, String payableAmount) {
        return new OrderRecord(
                id,
                "ORD-" + subOrderId,
                USER_ID,
                "momo",
                "/avatar.png",
                1001L,
                "Product",
                "/product.png",
                new BigDecimal(payableAmount),
                null,
                "Momo",
                "13800000000",
                "Beijing",
                null,
                OrderStatus.PENDING_PAYMENT.label(),
                null,
                false,
                CHECKOUT_ID,
                subOrderId,
                shopId,
                new BigDecimal(originalAmount),
                new BigDecimal(discountAmount),
                "checkout-key-1");
    }
}
