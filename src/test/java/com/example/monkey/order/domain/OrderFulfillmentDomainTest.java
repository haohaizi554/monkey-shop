package com.example.monkey.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.monkey.shared.domain.exception.BusinessException;
import org.junit.jupiter.api.Test;

class OrderFulfillmentDomainTest {

    @Test
    void shipmentAndReceiptAdvanceQuantityCountersWithoutBreakingInvariants() {
        OrderFulfillmentItem item = new OrderFulfillmentItem(1L, 10L, 7L, "Momo", 3, 0, 0, "PENDING");

        OrderFulfillmentItem partiallyShipped = item.ship(2);
        OrderFulfillmentItem partiallyReceived = partiallyShipped.receive(1);

        assertThat(partiallyShipped.shippedQuantity()).isEqualTo(2);
        assertThat(partiallyShipped.status()).isEqualTo("PARTIALLY_SHIPPED");
        assertThat(partiallyReceived.receivedQuantity()).isEqualTo(1);
        assertThat(partiallyReceived.status()).isEqualTo("PARTIALLY_RECEIVED");
    }

    @Test
    void rejectsOvershipAndOverReceipt() {
        OrderFulfillmentItem item = new OrderFulfillmentItem(1L, 10L, 7L, "Momo", 1, 0, 0, "PENDING");

        assertThatThrownBy(() -> item.ship(2)).isInstanceOf(BusinessException.class);

        OrderFulfillmentItem shipped = item.ship(1);
        assertThatThrownBy(() -> shipped.receive(2)).isInstanceOf(BusinessException.class);
    }
}
