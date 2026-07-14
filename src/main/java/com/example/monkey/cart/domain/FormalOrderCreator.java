package com.example.monkey.cart.domain;

import com.example.monkey.order.domain.CheckoutOrderCommand;
import java.util.List;

public interface FormalOrderCreator {

    List<Long> create(CheckoutOrderCommand command);
}
