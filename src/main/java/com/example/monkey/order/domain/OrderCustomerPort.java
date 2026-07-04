package com.example.monkey.order.domain;

import com.example.monkey.order.domain.OrderStore.AddressRecord;
import com.example.monkey.order.domain.OrderStore.BuyerRecord;
import java.util.Optional;

public interface OrderCustomerPort {

    Optional<AddressRecord> findAddressById(Long addressId);

    Optional<BuyerRecord> findBuyerById(Long userId);
}
