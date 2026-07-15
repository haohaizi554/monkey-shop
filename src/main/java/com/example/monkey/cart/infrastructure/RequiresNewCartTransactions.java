package com.example.monkey.cart.infrastructure;

import com.example.monkey.cart.application.CartTransactions;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class RequiresNewCartTransactions implements CartTransactions {

    private final TransactionTemplate transactionTemplate;

    public RequiresNewCartTransactions(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public <T> T execute(Supplier<T> action) {
        return transactionTemplate.execute(status -> action.get());
    }
}
