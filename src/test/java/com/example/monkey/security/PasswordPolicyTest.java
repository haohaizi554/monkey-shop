package com.example.monkey.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.example.monkey.domain.user.PasswordCompromiseChecker;
import com.example.monkey.domain.user.UserPasswordPolicy.PasswordPolicyResult;
import com.example.monkey.shared.api.ErrorCode;
import com.example.monkey.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

    @Test
    void acceptsPasswordWithRequiredComplexity() {
        PasswordPolicy passwordPolicy = new PasswordPolicy();

        PasswordPolicyResult result = passwordPolicy.validate("StrongPass1!");

        assertThat(result.valid()).isTrue();
        assertThat(result.violations()).isEmpty();
    }

    @Test
    void rejectsShortCommonShapePassword() {
        PasswordPolicy passwordPolicy = new PasswordPolicy();

        PasswordPolicyResult result = passwordPolicy.validate("Password1");

        assertThat(result.valid()).isFalse();
        assertThat(result.violations()).contains("password must be at least 10 characters");
        assertThat(result.violations()).contains("password must contain a special character");
    }

    @Test
    void throwsValidationExceptionForUserFacingPolicyFailures() {
        PasswordPolicy passwordPolicy = new PasswordPolicy();

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> passwordPolicy.validateOrThrow("Password1"))
                .withMessage("password policy violation: password must be at least 10 characters; "
                        + "password must contain a special character")
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    void rejectsWhitespaceInPassword() {
        PasswordPolicy passwordPolicy = new PasswordPolicy();

        PasswordPolicyResult result = passwordPolicy.validate("Strong Pass1!");

        assertThat(result.valid()).isFalse();
        assertThat(result.violations()).contains("password must not contain whitespace");
    }

    @Test
    void rejectsPasswordFoundInBreachCorpus() {
        PasswordPolicy passwordPolicy = new PasswordPolicy(
                password -> PasswordCompromiseChecker.PasswordCompromiseCheckResult.compromisedPassword());

        PasswordPolicyResult result = passwordPolicy.validate("StrongPass1!");

        assertThat(result.valid()).isFalse();
        assertThat(result.violations()).contains("password was found in a breach corpus");
    }

    @Test
    void rejectsPasswordWhenBreachCheckIsUnavailable() {
        PasswordPolicy passwordPolicy =
                new PasswordPolicy(password -> PasswordCompromiseChecker.PasswordCompromiseCheckResult.unavailable());

        PasswordPolicyResult result = passwordPolicy.validate("StrongPass1!");

        assertThat(result.valid()).isFalse();
        assertThat(result.violations()).contains("password breach check unavailable");
    }

    @Test
    void skipsBreachCheckWhenLocalPolicyFails() {
        CountingCompromiseChecker checker = new CountingCompromiseChecker();
        PasswordPolicy passwordPolicy = new PasswordPolicy(checker);

        PasswordPolicyResult result = passwordPolicy.validate("Password1");

        assertThat(result.valid()).isFalse();
        assertThat(checker.calls).isZero();
    }

    private static final class CountingCompromiseChecker implements PasswordCompromiseChecker {

        private int calls;

        @Override
        public PasswordCompromiseCheckResult check(String password) {
            calls++;
            return PasswordCompromiseCheckResult.safe();
        }
    }
}
