package com.example.monkey.security;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class PasswordPolicy {

    private static final int MIN_LENGTH = 10;

    public PasswordPolicyResult validate(String password) {
        List<String> violations = new ArrayList<>();
        if (!StringUtils.hasText(password)) {
            violations.add("password is required");
            return new PasswordPolicyResult(false, violations);
        }
        if (password.length() < MIN_LENGTH) {
            violations.add("password must be at least " + MIN_LENGTH + " characters");
        }
        if (password.chars().noneMatch(Character::isLowerCase)) {
            violations.add("password must contain a lowercase letter");
        }
        if (password.chars().noneMatch(Character::isUpperCase)) {
            violations.add("password must contain an uppercase letter");
        }
        if (password.chars().noneMatch(Character::isDigit)) {
            violations.add("password must contain a digit");
        }
        if (password.chars().noneMatch(PasswordPolicy::isSpecialCharacter)) {
            violations.add("password must contain a special character");
        }
        if (password.chars().anyMatch(Character::isWhitespace)) {
            violations.add("password must not contain whitespace");
        }
        return new PasswordPolicyResult(violations.isEmpty(), violations);
    }

    public String validateForUserMessage(String password) {
        PasswordPolicyResult result = validate(password);
        return result.valid() ? null : "error:password policy violation: " + String.join("; ", result.violations());
    }

    private static boolean isSpecialCharacter(int value) {
        return !Character.isLetterOrDigit(value) && !Character.isWhitespace(value);
    }

    public record PasswordPolicyResult(boolean valid, List<String> violations) {}
}
