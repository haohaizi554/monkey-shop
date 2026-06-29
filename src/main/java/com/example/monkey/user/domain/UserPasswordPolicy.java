package com.example.monkey.user.domain;

import java.util.List;

public interface UserPasswordPolicy {

    PasswordPolicyResult validate(String password);

    void validateOrThrow(String password);

    record PasswordPolicyResult(boolean valid, List<String> violations) {

        public PasswordPolicyResult {
            violations = violations == null ? List.of() : List.copyOf(violations);
        }
    }
}
