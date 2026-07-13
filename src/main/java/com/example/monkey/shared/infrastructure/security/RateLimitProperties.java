package com.example.monkey.shared.infrastructure.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "monkeyshop.rate-limit")
public record RateLimitProperties(@Valid @NotNull Register register) {

    private static final long DEFAULT_REGISTER_CAPACITY = 120L;
    private static final Duration DEFAULT_REGISTER_WINDOW = Duration.ofHours(1);

    public RateLimitProperties {
        register = register == null ? Register.defaults() : register;
    }

    public static RateLimitProperties defaults() {
        return new RateLimitProperties(Register.defaults());
    }

    public record Register(
            @Min(1) long edgeCapacity,
            @NotNull Duration edgeWindow,
            @Min(1) long identityCapacity,
            @NotNull Duration identityWindow) {

        static Register defaults() {
            return new Register(
                    DEFAULT_REGISTER_CAPACITY,
                    DEFAULT_REGISTER_WINDOW,
                    DEFAULT_REGISTER_CAPACITY,
                    DEFAULT_REGISTER_WINDOW);
        }
    }
}
