package com.example.monkey.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.monkey.logistics.domain.LogisticsCarrier;
import com.example.monkey.logistics.domain.LogisticsTracking;
import com.example.monkey.logistics.domain.TrackingStatus;
import com.example.monkey.logistics.infrastructure.LogisticsGatewayEnvironmentGuard;
import com.example.monkey.logistics.infrastructure.SandboxLogisticsGateway;
import com.example.monkey.logistics.infrastructure.UnavailableLogisticsGateway;
import com.example.monkey.payment.domain.PaymentMethod;
import com.example.monkey.payment.domain.PaymentOrder;
import com.example.monkey.payment.domain.PaymentStatus;
import com.example.monkey.payment.infrastructure.PaymentGatewayEnvironmentGuard;
import com.example.monkey.payment.infrastructure.SandboxPaymentGateway;
import com.example.monkey.payment.infrastructure.UnavailablePaymentGateway;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class GatewayProviderSafetyTest {

    private static final String PAYMENT_INFRASTRUCTURE = "src/main/java/com/example/monkey/payment/infrastructure/";
    private static final String LOGISTICS_INFRASTRUCTURE = "src/main/java/com/example/monkey/logistics/infrastructure/";

    @Test
    void unavailablePaymentGatewayRejectsCreateQueryAndRefund() throws Exception {
        UnavailablePaymentGateway gateway = new UnavailablePaymentGateway();
        PaymentOrder payment = payment();

        assertServiceUnavailable(() -> gateway.create(payment, "merchant-token"));
        assertServiceUnavailable(() -> gateway.query(payment));
        assertServiceUnavailable(() -> gateway.refund(payment, new BigDecimal("10.00"), "merchant-token"));
    }

    @Test
    void unavailableLogisticsGatewayRejectsShipmentCreation() throws Exception {
        UnavailableLogisticsGateway gateway = new UnavailableLogisticsGateway();

        assertServiceUnavailable(() -> gateway.createShipment(tracking()));
    }

    @Test
    void prodAndStagingUnavailableSelectionExcludesSandboxBeans() {
        for (String profile : new String[] {"prod", "staging"}) {
            new ApplicationContextRunner()
                    .withUserConfiguration(PaymentGatewayConfiguration.class, LogisticsGatewayConfiguration.class)
                    .withPropertyValues(
                            "spring.profiles.active=" + profile,
                            "app.payment.gateway=unavailable",
                            "app.logistics.gateway=unavailable")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBeansOfType(UnavailablePaymentGateway.class))
                                .containsKey(UnavailablePaymentGateway.class.getName());
                        assertThat(context).doesNotHaveBean(SandboxPaymentGateway.class);
                        assertThat(context.getBeansOfType(UnavailableLogisticsGateway.class))
                                .containsKey(UnavailableLogisticsGateway.class.getName());
                        assertThat(context).doesNotHaveBean(SandboxLogisticsGateway.class);
                    });
        }
    }

    @Test
    void devSandboxSelectionStillProvidesBothSandboxBeans() {
        new ApplicationContextRunner()
                .withUserConfiguration(PaymentGatewayConfiguration.class, LogisticsGatewayConfiguration.class)
                .withPropertyValues(
                        "spring.profiles.active=dev", "app.payment.gateway=sandbox", "app.logistics.gateway=sandbox")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(SandboxPaymentGateway.class))
                            .containsKey(SandboxPaymentGateway.class.getName());
                    assertThat(context).doesNotHaveBean(UnavailablePaymentGateway.class);
                    assertThat(context.getBeansOfType(SandboxLogisticsGateway.class))
                            .containsKey(SandboxLogisticsGateway.class.getName());
                    assertThat(context).doesNotHaveBean(UnavailableLogisticsGateway.class);
                });
    }

    @Test
    void explicitSandboxSelectionFailsClosedInProdAndStaging() {
        for (String profile : new String[] {"prod", "staging"}) {
            new ApplicationContextRunner()
                    .withUserConfiguration(PaymentGatewayConfiguration.class, LogisticsGatewayConfiguration.class)
                    .withPropertyValues(
                            "spring.profiles.active=" + profile,
                            "app.payment.gateway=sandbox",
                            "app.logistics.gateway=sandbox")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasRootCauseInstanceOf(IllegalStateException.class)
                                .hasStackTraceContaining("sandbox")
                                .hasStackTraceContaining("forbidden");
                    });
        }
    }

    @Test
    void sandboxBeansRequireAnExplicitSandboxProperty() throws IOException {
        assertThat(read(PAYMENT_INFRASTRUCTURE + "SandboxPaymentGateway.java"))
                .contains("havingValue = \"sandbox\"")
                .contains("matchIfMissing = false")
                .doesNotContain("matchIfMissing = true");
        assertThat(read(LOGISTICS_INFRASTRUCTURE + "SandboxLogisticsGateway.java"))
                .contains("havingValue = \"sandbox\"")
                .contains("matchIfMissing = false")
                .doesNotContain("matchIfMissing = true");
    }

    @Test
    void devKeepsSandboxWhileProdAndStagingSelectUnavailable() throws IOException {
        assertThat(read("src/main/resources/application-dev.yml"))
                .containsPattern("(?m)^  payment:\\s*\\r?\\n(?:    .*\\r?\\n)*    gateway:\\s+sandbox\\s*$")
                .containsPattern("(?m)^  logistics:\\s*\\r?\\n(?:    .*\\r?\\n)*    gateway:\\s+sandbox\\s*$");
        for (String profile : new String[] {"prod", "staging"}) {
            assertThat(read("src/main/resources/application-" + profile + ".yml"))
                    .containsPattern("(?m)^  payment:\\s*\\r?\\n(?:    .*\\r?\\n)*    gateway:\\s+unavailable\\s*$")
                    .containsPattern("(?m)^  logistics:\\s*\\r?\\n(?:    .*\\r?\\n)*    gateway:\\s+unavailable\\s*$");
        }
    }

    @Test
    void sharedEnvironmentGatewayGuardsRejectExplicitSandbox() throws IOException {
        assertThat(read(PAYMENT_INFRASTRUCTURE + "PaymentGatewayEnvironmentGuard.java"))
                .contains("@Profile({\"prod\", \"staging\"})")
                .contains("sandbox")
                .contains("forbidden");
        assertThat(read(LOGISTICS_INFRASTRUCTURE + "LogisticsGatewayEnvironmentGuard.java"))
                .contains("@Profile({\"prod\", \"staging\"})")
                .contains("sandbox")
                .contains("forbidden");
    }

    @Test
    void helmDefaultsAndSharedOverridesAreExplicitlyUnavailable() throws IOException {
        for (String valuesFile : new String[] {
            "helm/monkeyshop/values.yaml", "helm/monkeyshop/values-prod.yaml", "helm/monkeyshop/values-staging.yaml"
        }) {
            assertThat(read(valuesFile))
                    .containsPattern("(?m)^\\s+APP_PAYMENT_GATEWAY:\\s+unavailable\\s*$")
                    .containsPattern("(?m)^\\s+APP_LOGISTICS_GATEWAY:\\s+unavailable\\s*$")
                    .doesNotContainPattern("(?m)^\\s+APP_(?:PAYMENT|LOGISTICS)_GATEWAY:\\s+sandbox\\s*$");
        }
    }

    private static void assertServiceUnavailable(ThrowingCallable callable) {
        assertThatThrownBy(callable)
                .isInstanceOf(BusinessException.class)
                .satisfies(throwable -> assertThat(((BusinessException) throwable).errorCode())
                        .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
    }

    private static PaymentOrder payment() {
        LocalDateTime now = LocalDateTime.parse("2026-08-28T08:00:00");
        return new PaymentOrder(
                1L,
                "PAYMENT-1",
                2L,
                3L,
                PaymentMethod.WECHAT,
                new BigDecimal("10.00"),
                new BigDecimal("10.00"),
                BigDecimal.ZERO,
                PaymentStatus.PAID,
                "payment-key",
                "provider-payment-1",
                null,
                null,
                null,
                now,
                now,
                now);
    }

    private static LogisticsTracking tracking() {
        LocalDateTime now = LocalDateTime.parse("2026-08-28T08:00:00");
        return new LogisticsTracking(
                1L,
                "YTO-1",
                2L,
                3L,
                LogisticsCarrier.YTO,
                TrackingStatus.ORDERED,
                null,
                null,
                null,
                null,
                "Hainan",
                "Haikou",
                "Longhua",
                "Hainan Haikou Longhua",
                new BigDecimal("10.00"),
                48,
                "shipment-key",
                null,
                null,
                null,
                null,
                now,
                now);
    }

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Configuration(proxyBeanMethods = false)
    @Import({SandboxPaymentGateway.class, UnavailablePaymentGateway.class, PaymentGatewayEnvironmentGuard.class})
    static class PaymentGatewayConfiguration {

        @Bean
        Object paymentGatewayDependency(com.example.monkey.payment.domain.PaymentGateway gateway) {
            return gateway;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import({SandboxLogisticsGateway.class, UnavailableLogisticsGateway.class, LogisticsGatewayEnvironmentGuard.class})
    static class LogisticsGatewayConfiguration {

        @Bean
        Object logisticsGatewayDependency(com.example.monkey.logistics.domain.LogisticsGateway gateway) {
            return gateway;
        }
    }
}
