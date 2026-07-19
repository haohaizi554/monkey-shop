package com.example.monkey.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import org.junit.jupiter.api.Test;

class ControllerEndpointInventoryTest {

    @Test
    void discoversEveryCanonicalControllerMappingWithoutAliasesOrDuplicates() {
        var endpoints = ControllerEndpointInventory.canonicalApiEndpoints();

        assertThat(endpoints).isNotEmpty();
        assertThat(endpoints).allSatisfy(endpoint -> {
            assertThat(endpoint.path()).startsWith("/api/v1/");
            assertThat(endpoint.method()).isNotBlank();
            assertThat(endpoint.authorization()).isNotBlank();
        });
        assertThat(new HashSet<>(endpoints)).hasSameSizeAs(endpoints);
        assertThat(endpoints)
                .extracting(ControllerEndpointInventory.Endpoint::key)
                .contains(
                        "POST /api/v1/payments/callback",
                        "POST /api/v1/logistics/webhook",
                        "POST /api/v1/users/logout",
                        "GET /api/v1/membership/admin/{userId}/dashboard",
                        "POST /api/v1/membership/admin/{userId}/points/earn",
                        "POST /api/v1/membership/admin/{userId}/level");
    }
}
