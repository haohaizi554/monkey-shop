package com.example.monkey.order.infrastructure;

import com.example.monkey.cart.domain.FormalOrderCreator;
import com.example.monkey.order.application.CheckoutOrderApplicationService;
import com.example.monkey.order.domain.CheckoutOrderCommand;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class OrderFormalOrderCreator implements FormalOrderCreator {

    private final CheckoutOrderApplicationService applicationService;

    public OrderFormalOrderCreator(CheckoutOrderApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @Override
    public List<Long> create(CheckoutOrderCommand command) {
        return applicationService.create(command);
    }
}
