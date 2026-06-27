package com.example.monkey.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

    private final PasswordPolicy passwordPolicy = new PasswordPolicy();

    @Test
    void acceptsPasswordWithRequiredComplexity() {
        PasswordPolicy.PasswordPolicyResult result = passwordPolicy.validate("StrongPass1!");

        assertThat(result.valid()).isTrue();
        assertThat(result.violations()).isEmpty();
    }

    @Test
    void rejectsShortCommonShapePassword() {
        PasswordPolicy.PasswordPolicyResult result = passwordPolicy.validate("Password1");

        assertThat(result.valid()).isFalse();
        assertThat(result.violations()).contains("password must be at least 10 characters");
        assertThat(result.violations()).contains("password must contain a special character");
    }

    @Test
    void rejectsWhitespaceInPassword() {
        PasswordPolicy.PasswordPolicyResult result = passwordPolicy.validate("Strong Pass1!");

        assertThat(result.valid()).isFalse();
        assertThat(result.violations()).contains("password must not contain whitespace");
    }
}
