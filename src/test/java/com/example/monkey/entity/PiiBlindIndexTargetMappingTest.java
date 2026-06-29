package com.example.monkey.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.monkey.order.infrastructure.Order;
import com.example.monkey.shared.domain.privacy.PhoneBlindIndexTarget;
import com.example.monkey.user.infrastructure.Address;
import com.example.monkey.user.infrastructure.User;
import org.junit.jupiter.api.Test;

class PiiBlindIndexTargetMappingTest {

    @Test
    void userMapsPhoneBlindIndexContractToPhoneHmacColumn() {
        User user = new User();
        user.setPhone("+86 138-0000-0000");

        PhoneBlindIndexTarget target = user;
        target.setPhoneBlindIndex("hash");

        assertThat(target.phoneValueForBlindIndex()).isEqualTo("+86 138-0000-0000");
        assertThat(user.getPhoneHmac()).isEqualTo("hash");
    }

    @Test
    void addressMapsPhoneBlindIndexContractToPhoneHmacColumn() {
        Address address = new Address();
        address.setPhone("+86 138-0000-0000");

        PhoneBlindIndexTarget target = address;
        target.setPhoneBlindIndex("hash");

        assertThat(target.phoneValueForBlindIndex()).isEqualTo("+86 138-0000-0000");
        assertThat(address.getPhoneHmac()).isEqualTo("hash");
    }

    @Test
    void orderMapsPhoneBlindIndexContractToReceiverPhoneHmacColumn() {
        Order order = new Order();
        order.setReceiverPhone("+86 138-0000-0000");

        PhoneBlindIndexTarget target = order;
        target.setPhoneBlindIndex("hash");

        assertThat(target.phoneValueForBlindIndex()).isEqualTo("+86 138-0000-0000");
        assertThat(order.getReceiverPhoneHmac()).isEqualTo("hash");
    }
}
