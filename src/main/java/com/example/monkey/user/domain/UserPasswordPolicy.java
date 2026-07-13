package com.example.monkey.user.domain;

import java.util.List;

public interface UserPasswordPolicy {

    PasswordPolicyResult validate(String password);

    void validateOrThrow(String password);

    PasswordPolicyMetadata metadata();

    record PasswordPolicyResult(boolean valid, List<String> violations) {

        public PasswordPolicyResult {
            violations = violations == null ? List.of() : List.copyOf(violations);
        }
    }

    record PasswordPolicyMetadata(
            int minLength,
            boolean requireUppercase,
            boolean requireLowercase,
            boolean requireDigit,
            boolean requireSpecial,
            boolean forbidWhitespace) {}
}
