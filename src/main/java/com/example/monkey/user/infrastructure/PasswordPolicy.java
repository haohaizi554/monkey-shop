package com.example.monkey.user.infrastructure;

import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.user.domain.PasswordCompromiseChecker;
import com.example.monkey.user.domain.UserPasswordPolicy;
import com.example.monkey.user.domain.UserPasswordPolicy.PasswordPolicyResult;
import java.util.ArrayList;
import java.util.List;
import org.passay.DefaultPasswordValidator;
import org.passay.PasswordData;
import org.passay.PasswordValidator;
import org.passay.ValidationResult;
import org.passay.data.EnglishCharacterData;
import org.passay.rule.CharacterRule;
import org.passay.rule.LengthRule;
import org.passay.rule.Rule;
import org.passay.rule.WhitespaceRule;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class PasswordPolicy implements UserPasswordPolicy {

    private static final int MIN_LENGTH = 10;
    private static final String COMPROMISED_PASSWORD_MESSAGE = "password was found in a breach corpus";
    private static final String COMPROMISE_CHECK_UNAVAILABLE_MESSAGE = "password breach check unavailable";

    private final PasswordCompromiseChecker compromiseChecker;
    private final PasswordValidator validator;

    public PasswordPolicy() {
        this(PasswordCompromiseChecker.disabled());
    }

    @Autowired
    public PasswordPolicy(PasswordCompromiseChecker compromiseChecker) {
        this(compromiseChecker, defaultValidator());
    }

    PasswordPolicy(PasswordCompromiseChecker compromiseChecker, PasswordValidator validator) {
        this.compromiseChecker = compromiseChecker;
        this.validator = validator;
    }

    @Override
    public PasswordPolicyResult validate(String password) {
        List<String> violations = new ArrayList<>();
        if (!StringUtils.hasText(password)) {
            violations.add("password is required");
            return new PasswordPolicyResult(false, violations);
        }

        ValidationResult ruleResult = validator.validate(new PasswordData(password));
        if (!ruleResult.isValid()) {
            addLocalRuleMessages(password, violations);
        }
        if (violations.isEmpty()) {
            PasswordCompromiseChecker.PasswordCompromiseCheckResult compromiseResult =
                    compromiseChecker.check(password);
            if (compromiseResult.compromised()) {
                violations.add(COMPROMISED_PASSWORD_MESSAGE);
            } else if (compromiseResult.checkUnavailable()) {
                violations.add(COMPROMISE_CHECK_UNAVAILABLE_MESSAGE);
            }
        }
        return new PasswordPolicyResult(violations.isEmpty(), violations);
    }

    @Override
    public void validateOrThrow(String password) {
        PasswordPolicyResult result = validate(password);
        if (!result.valid()) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "password policy violation: " + String.join("; ", result.violations()));
        }
    }

    private static boolean isSpecialCharacter(int value) {
        return !Character.isLetterOrDigit(value) && !Character.isWhitespace(value);
    }

    private static void addLocalRuleMessages(String password, List<String> violations) {
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
        if (violations.isEmpty()) {
            violations.add("password does not meet complexity requirements");
        }
    }

    private static PasswordValidator defaultValidator() {
        List<Rule> rules = List.of(
                new LengthRule(MIN_LENGTH, Integer.MAX_VALUE),
                new CharacterRule(EnglishCharacterData.LowerCase, 1),
                new CharacterRule(EnglishCharacterData.UpperCase, 1),
                new CharacterRule(EnglishCharacterData.Digit, 1),
                new CharacterRule(EnglishCharacterData.Special, 1),
                new WhitespaceRule());
        return new DefaultPasswordValidator(rules);
    }
}
