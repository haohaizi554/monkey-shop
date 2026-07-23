package com.example.monkey.payment.infrastructure;

import com.example.monkey.payment.application.PaymentTransactions;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class RequiresNewPaymentTransactions implements PaymentTransactions {

    private final TransactionTemplate requiresNewTransaction;
    private final TransactionTemplate withoutTransaction;

    public RequiresNewPaymentTransactions(PlatformTransactionManager transactionManager) {
        this.requiresNewTransaction = new TransactionTemplate(transactionManager);
        this.requiresNewTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.withoutTransaction = new TransactionTemplate(transactionManager);
        this.withoutTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    }

    @Override
    public <T> T execute(Supplier<T> action) {
        return requiresNewTransaction.execute(status -> action.get());
    }

    @Override
    public <T> T executeWithoutTransaction(Supplier<T> action) {
        return withoutTransaction.execute(status -> action.get());
    }
}
