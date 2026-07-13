package com.example.monkey.shared.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.monkey.MonkeyShopApplication;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.context.properties.bind.BindResult;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

class RateLimitPropertiesTest {

    @Test
    void exposesTypedDefaultRegistrationQuotaContract() {
        ConfigurationProperties annotation = RateLimitProperties.class.getAnnotation(ConfigurationProperties.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.prefix()).isEqualTo("monkeyshop.rate-limit");

        assertRegister(RateLimitProperties.defaults().register(), 120, Duration.ofHours(1), 120, Duration.ofHours(1));
    }

    @Test
    void bindsExactRegistrationQuotasFromEveryRuntimeProfile() throws IOException {
        RateLimitProperties defaults = bind("application.yml");
        RateLimitProperties dev = bind("application.yml", "application-dev.yml");
        RateLimitProperties prod = bind("application.yml", "application-prod.yml");
        RateLimitProperties staging = bind("application.yml", "application-staging.yml");

        assertRegister(defaults.register(), 120, Duration.ofHours(1), 120, Duration.ofHours(1));
        assertRegister(dev.register(), 120, Duration.ofHours(1), 120, Duration.ofHours(1));
        assertRegister(prod.register(), 20, Duration.ofMinutes(15), 5, Duration.ofHours(1));
        assertRegister(staging.register(), 20, Duration.ofMinutes(15), 5, Duration.ofHours(1));
    }

    @Test
    void applicationRegistersTypedConfigurationProperties() {
        assertThat(MonkeyShopApplication.class.isAnnotationPresent(ConfigurationPropertiesScan.class))
                .isTrue();
    }

    private static RateLimitProperties bind(String... resources) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        for (String resource : resources) {
            List<PropertySource<?>> loaded = loader.load(resource, new ClassPathResource(resource));
            for (PropertySource<?> propertySource : loaded) {
                environment.getPropertySources().addFirst(propertySource);
            }
        }
        BindResult<RateLimitProperties> result =
                Binder.get(environment).bind("monkeyshop.rate-limit", Bindable.of(RateLimitProperties.class));
        assertThat(result.isBound())
                .as("properties from %s", (Object) resources)
                .isTrue();
        return result.get();
    }

    private static void assertRegister(
            RateLimitProperties.Register register,
            long edgeCapacity,
            Duration edgeWindow,
            long identityCapacity,
            Duration identityWindow) {
        assertThat(register.edgeCapacity()).isEqualTo(edgeCapacity);
        assertThat(register.edgeWindow()).isEqualTo(edgeWindow);
        assertThat(register.identityCapacity()).isEqualTo(identityCapacity);
        assertThat(register.identityWindow()).isEqualTo(identityWindow);
    }
}
