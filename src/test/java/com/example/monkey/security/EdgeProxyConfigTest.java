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
        assertThat(config).contains("X-Content-Type-Options");
        assertThat(config).contains("Referrer-Policy");
        assertThat(config).contains("strict-origin-when-cross-origin");
        assertThat(config).contains("Permissions-Policy");
        assertThat(config).contains("camera=(), microphone=(), geolocation=(), payment=()");
        assertThat(config).contains("Content-Security-Policy is emitted by Spring Security");
        assertThat(config).doesNotContain("add_header Content-Security-Policy");
        assertThat(config).doesNotContain("proxy_hide_header Content-Security-Policy");
        assertThat(config).contains("proxy_pass http://monkeyshop_app;");
        assertThat(config).contains("proxy_set_header Host monkeyshop.example.com;");
        assertThat(config).contains("proxy_set_header X-Forwarded-Host monkeyshop.example.com;");
        assertThat(config).contains("proxy_set_header X-Forwarded-Proto https;");
        assertThat(config).contains("proxy_set_header X-Forwarded-Port 443;");
        assertThat(config).contains("proxy_set_header Forwarded \"\";");
        assertThat(config).contains("client_max_body_size 6m;");
        assertThat(config).contains("client_body_timeout 10s;");
        assertThat(config).contains("client_header_timeout 10s;");
        assertThat(config).contains("limit_req_zone $binary_remote_addr zone=monkeyshop_api:10m rate=10r/s;");
        assertThat(config).contains("limit_req zone=monkeyshop_api burst=20 nodelay;");
        assertThat(config).contains("limit_rate 512k;");
        assertThat(config).contains("location = /api/.env");
        assertThat(config).contains("location = /admin/secret");
    }

    @Test
    void productionProfilesDisableFrameworkTrustAndRequireExplicitProxyCidrs() throws IOException {
        String prod = Files.readString(Path.of("src/main/resources/application-prod.yml"), StandardCharsets.UTF_8);
        String staging =
                Files.readString(Path.of("src/main/resources/application-staging.yml"), StandardCharsets.UTF_8);
        String helmValues = Files.readString(Path.of("helm/monkeyshop/values.yaml"), StandardCharsets.UTF_8);

        assertThat(prod)
                .contains("forward-headers-strategy: none")
                .contains("trusted-proxy-cidrs: ${APP_SECURITY_TRUSTED_PROXY_CIDRS:}");
        assertThat(staging)
                .contains("forward-headers-strategy: none")
                .contains("trusted-proxy-cidrs: ${APP_SECURITY_TRUSTED_PROXY_CIDRS:}");
        assertThat(helmValues)
                .contains("SERVER_FORWARD_HEADERS_STRATEGY: none")
                .contains("APP_SECURITY_TRUSTED_PROXY_CIDRS: \"\"");
    }

    @Test
    void productionProfilesRequireRedisBackedJwtTokenStore() throws IOException {
        String prod = Files.readString(Path.of("src/main/resources/application-prod.yml"), StandardCharsets.UTF_8);
        String staging =
                Files.readString(Path.of("src/main/resources/application-staging.yml"), StandardCharsets.UTF_8);

        assertThat(prod).contains("require-redis-token-store: ${APP_JWT_REQUIRE_REDIS_TOKEN_STORE:true}");
        assertThat(staging).contains("require-redis-token-store: ${APP_JWT_REQUIRE_REDIS_TOKEN_STORE:true}");
    }

    @Test
    void productionProfilesRequireRedisBackedAuthChallengeState() throws IOException {
        String prod = Files.readString(Path.of("src/main/resources/application-prod.yml"), StandardCharsets.UTF_8);
        String staging =
                Files.readString(Path.of("src/main/resources/application-staging.yml"), StandardCharsets.UTF_8);

        assertThat(prod).contains("require-redis-state: ${APP_AUTH_REQUIRE_REDIS_STATE:true}");
        assertThat(staging).contains("require-redis-state: ${APP_AUTH_REQUIRE_REDIS_STATE:true}");
    }

    @Test
    void productionProfilesEnableExternalHumanVerificationAndPiiEncryption() throws IOException {
        String prod = Files.readString(Path.of("src/main/resources/application-prod.yml"), StandardCharsets.UTF_8);
        String staging =
                Files.readString(Path.of("src/main/resources/application-staging.yml"), StandardCharsets.UTF_8);

        assertThat(prod).contains("provider: ${APP_AUTH_CAPTCHA_PROVIDER:turnstile}");
        assertThat(staging).contains("provider: ${APP_AUTH_CAPTCHA_PROVIDER:turnstile}");
        assertThat(prod).contains("enabled: ${APP_PII_ENCRYPTION_ENABLED:true}");
        assertThat(staging).contains("enabled: ${APP_PII_ENCRYPTION_ENABLED:true}");
        assertThat(prod).contains("require-redis-state: ${APP_RATE_LIMIT_REQUIRE_REDIS_STATE:true}");
        assertThat(staging).contains("require-redis-state: ${APP_RATE_LIMIT_REQUIRE_REDIS_STATE:true}");
    }
}
