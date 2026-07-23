package com.example.monkey.payment.application;

import java.util.function.Supplier;

public interface PaymentTransactions {

    <T> T execute(Supplier<T> action);

    default <T> T executeWithoutTransaction(Supplier<T> action) {
        return action.get();
    }
}
