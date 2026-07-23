package com.example.monkey.payment.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class PaymentMySqlContainerConfigurationTest {

    @Test
    void containerPropertiesOverrideTheH2TestDriver() {
        Map<String, Supplier<Object>> properties = new HashMap<>();

        PaymentMySqlContainerIntegrationTest.mysqlProperties(properties::put);

        assertThat(properties).containsKey("spring.datasource.url").containsKey("spring.datasource.driver-class-name");
        assertThat(properties.get("spring.datasource.driver-class-name").get()).isEqualTo("com.mysql.cj.jdbc.Driver");
    }
}
