package com.example.monkey.logistics.infrastructure;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@Profile({"prod", "staging"})
public final class LogisticsGatewayEnvironmentGuard {

    private final Environment environment;

    public LogisticsGatewayEnvironmentGuard(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void rejectSandboxGatewayInSharedEnvironment() {
        String configuredGateway =
                environment.getProperty("app.logistics.gateway", "").trim();
        if ("sandbox".equalsIgnoreCase(configuredGateway)) {
            throw new IllegalStateException(
                    "sandbox logistics gateway is forbidden in prod/staging; configure a real adapter or unavailable");
        }
    }
}
