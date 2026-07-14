package com.example.monkey.payment.application;

import java.util.function.Supplier;

public interface PaymentTransactions {

    <T> T execute(Supplier<T> action);
}
