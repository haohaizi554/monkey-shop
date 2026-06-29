package com.example.monkey.domain.user;

public interface PasswordCompromiseChecker {

    PasswordCompromiseCheckResult check(String password);

    static PasswordCompromiseChecker disabled() {
        return password -> PasswordCompromiseCheckResult.safe();
    }

    record PasswordCompromiseCheckResult(boolean compromised, boolean checkUnavailable) {

        public static PasswordCompromiseCheckResult safe() {
            return new PasswordCompromiseCheckResult(false, false);
        }

        public static PasswordCompromiseCheckResult compromisedPassword() {
            return new PasswordCompromiseCheckResult(true, false);
        }

        public static PasswordCompromiseCheckResult unavailable() {
            return new PasswordCompromiseCheckResult(false, true);
        }
    }
}
