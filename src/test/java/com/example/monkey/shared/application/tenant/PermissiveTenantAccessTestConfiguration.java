package com.example.monkey.shared.application.tenant;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration(proxyBeanMethods = false)
public class PermissiveTenantAccessTestConfiguration {

    @Bean
    TenantAccessGateway tenantAccessGateway() {
        return tenantId -> true;
    }
}
