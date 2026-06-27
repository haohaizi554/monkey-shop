package com.example.monkey.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class EdgeProxyConfigTest {

    @Test
    void nginxBaselineTerminatesTls13AndForwardsSecureRequestContext() throws IOException {
        String config = Files.readString(Path.of("deploy/nginx/monkeyshop.conf"), StandardCharsets.UTF_8);

        assertThat(config).contains("ssl_protocols TLSv1.3;");
        assertThat(config).contains("server_name monkeyshop.example.com;");
        assertThat(config).contains("return 301 https://monkeyshop.example.com$request_uri;");
        assertThat(config).contains("Strict-Transport-Security");
        assertThat(config).contains("includeSubDomains; preload");
        assertThat(config).contains("proxy_pass http://monkeyshop_app;");
        assertThat(config).contains("proxy_set_header Host monkeyshop.example.com;");
        assertThat(config).contains("proxy_set_header X-Forwarded-Host monkeyshop.example.com;");
        assertThat(config).contains("proxy_set_header X-Forwarded-Proto https;");
        assertThat(config).contains("proxy_set_header X-Forwarded-Port 443;");
        assertThat(config).contains("client_max_body_size 6m;");
    }

    @Test
    void productionProfilesTrustForwardedHeaders() throws IOException {
        String prod = Files.readString(Path.of("src/main/resources/application-prod.yml"), StandardCharsets.UTF_8);
        String staging = Files.readString(Path.of("src/main/resources/application-staging.yml"), StandardCharsets.UTF_8);

        assertThat(prod).contains("forward-headers-strategy: framework");
        assertThat(staging).contains("forward-headers-strategy: framework");
    }
}
