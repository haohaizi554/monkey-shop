package com.example.monkey.cart.application;

import java.util.function.Supplier;

public interface CartTransactions {

    <T> T execute(Supplier<T> action);
}
