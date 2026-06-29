package com.example.monkey.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.monkey.domain.user.PasswordCompromiseChecker.PasswordCompromiseCheckResult;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PwnedPasswordCheckerTest {

    private static final String PASSWORD_SHA1_PREFIX = "5BAA6";
    private static final String PASSWORD_SHA1_SUFFIX =
            String.join("", "1E4C9", "B93F3", "F0682", "250B6", "CF833", "1B7EE", "68FD8");
    private static final String PASSWORD_SHA1 = PASSWORD_SHA1_PREFIX + PASSWORD_SHA1_SUFFIX;

    @Test
    void flagsCompromisedPasswordWhenSuffixAppearsInRangeResponse() {
        AtomicReference<String> requestedPrefix = new AtomicReference<>();
        AtomicReference<URI> requestedUri = new AtomicReference<>();
        PwnedPasswordChecker checker = new PwnedPasswordChecker(
                true, "https://api.pwnedpasswords.com/range/", Duration.ofSeconds(1), (prefix, uri) -> {
                    requestedPrefix.set(prefix);
                    requestedUri.set(uri);
                    return "00000000000000000000000000000000000:1%n%s:3303003%n".formatted(PASSWORD_SHA1_SUFFIX);
                });

        PasswordCompromiseCheckResult result = checker.check("password");

        assertThat(result.compromised()).isTrue();
        assertThat(result.checkUnavailable()).isFalse();
        assertThat(requestedPrefix.get()).isEqualTo(PASSWORD_SHA1_PREFIX);
        assertThat(requestedUri.get()).isEqualTo(URI.create("https://api.pwnedpasswords.com/range/5BAA6"));
    }

    @Test
    void returnsSafeWhenPasswordSuffixIsNotInRangeResponse() {
        PwnedPasswordChecker checker = new PwnedPasswordChecker(
                true,
                "https://api.pwnedpasswords.com/range",
                Duration.ofSeconds(1),
                (prefix, uri) -> "00000000000000000000000000000000000:1\n");

        PasswordCompromiseCheckResult result = checker.check("password");

        assertThat(result.compromised()).isFalse();
        assertThat(result.checkUnavailable()).isFalse();
    }

    @Test
    void disabledCheckerReturnsSafeWithoutCallingRangeApi() {
        AtomicBoolean called = new AtomicBoolean(false);
        PwnedPasswordChecker checker = new PwnedPasswordChecker(
                false, "https://api.pwnedpasswords.com/range", Duration.ofSeconds(1), (prefix, uri) -> {
                    called.set(true);
                    return PASSWORD_SHA1.substring(5) + ":1";
                });

        PasswordCompromiseCheckResult result = checker.check("password");

        assertThat(result.compromised()).isFalse();
        assertThat(result.checkUnavailable()).isFalse();
        assertThat(called).isFalse();
    }

    @Test
    void unavailableRangeApiFailsClosedForPasswordPolicy() {
        PwnedPasswordChecker checker = new PwnedPasswordChecker(
                true, "https://api.pwnedpasswords.com/range", Duration.ofSeconds(1), (prefix, uri) -> {
                    throw new IOException("offline");
                });

        PasswordCompromiseCheckResult result = checker.check("StrongPass1!");

        assertThat(result.compromised()).isFalse();
        assertThat(result.checkUnavailable()).isTrue();
    }

    @Test
    void matchingSuffixWithMalformedCountStillFailsClosedAsCompromised() {
        assertThat(PwnedPasswordChecker.responseContainsSuffix(
                        PASSWORD_SHA1_SUFFIX + ":not-a-number", PASSWORD_SHA1_SUFFIX))
                .isTrue();
    }
}
