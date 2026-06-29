package com.example.monkey.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApiPathsTest {

    @Test
    void canonicalizesVersionedPluralResourcesToLegacyPolicyPaths() {
        assertThat(ApiPaths.canonicalize("/api/v1/addresses/1")).isEqualTo("/api/address/1");
        assertThat(ApiPaths.canonicalize("/api/v1/users/update-password")).isEqualTo("/api/user/update-password");
        assertThat(ApiPaths.canonicalize("/api/v1/uploads/product")).isEqualTo("/api/upload/product");
        assertThat(ApiPaths.canonicalize("/api/v1/orders/create")).isEqualTo("/api/orders/create");
    }

    @Test
    void preservesLegacyApiAndNonApiPaths() {
        assertThat(ApiPaths.canonicalize("/api/orders/create")).isEqualTo("/api/orders/create");
        assertThat(ApiPaths.canonicalize("/shop")).isEqualTo("/shop");
        assertThat(ApiPaths.canonicalize(null)).isNull();
    }
}
