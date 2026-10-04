package com.example.monkey.payment.infrastructure;

import jakarta.annotation.PostConstruct;
import org.springframework.core.env.Environment;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile({"prod", "staging"})
public final class PaymentGatewayEnvironmentGuard {

    private final Environment environment;

    public PaymentGatewayEnvironmentGuard(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void rejectSandboxGatewayInSharedEnvironment() {
        String configuredGateway = environment.getProperty("app.payment.gateway", "").trim();
        if ("sandbox".equalsIgnoreCase(configuredGateway)) {
            throw new IllegalStateException(
                    "sandbox payment gateway is forbidden in prod/staging; configure a real adapter or unavailable");
        }
    }
}
