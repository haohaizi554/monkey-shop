package com.example.monkey.shared.application.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class CaptchaChallengeResultTest {

    @Test
    void localChallengeExposesRenderingPropertiesAndDefensivelyCopiesContent() {
        byte[] originalContent = new byte[] {1, 2, 3};

        CaptchaChallengeResult result = CaptchaChallengeResult.local(
                "challenge-id", Duration.ofSeconds(45), true, "image/png", originalContent);
        originalContent[0] = 9;
        byte[] returnedContent = result.content();
        returnedContent[1] = 9;

        assertThat(result.provider()).isEqualTo("local");
        assertThat(result.siteKey()).isEmpty();
        assertThat(result.challengeId()).hasValue("challenge-id");
        assertThat(result.ttl()).isEqualTo(Duration.ofSeconds(45));
        assertThat(result.cookieSecure()).isTrue();
        assertThat(result.contentType()).hasValue("image/png");
        assertThat(result.content()).containsExactly(1, 2, 3);
        assertThat(result.externalProvider()).isFalse();
    }

    @Test
    void externalChallengeExposesProviderMetadataWithoutBodyOrCookieState() {
        CaptchaChallengeResult result = CaptchaChallengeResult.external("turnstile", "site-key");

        assertThat(result.provider()).isEqualTo("turnstile");
        assertThat(result.siteKey()).isEqualTo("site-key");
        assertThat(result.challengeId()).isEmpty();
        assertThat(result.ttl()).isZero();
        assertThat(result.cookieSecure()).isFalse();
        assertThat(result.contentType()).isEmpty();
        assertThat(result.content()).isEmpty();
        assertThat(result.externalProvider()).isTrue();
    }

    @Test
    void nullContentIsNormalizedToAnEmptyBody() {
        CaptchaChallengeResult result =
                CaptchaChallengeResult.local("challenge-id", Duration.ofMinutes(1), false, "image/jpeg", null);

        assertThat(result.challengeId()).hasValue("challenge-id");
        assertThat(result.content()).isEmpty();
        assertThat(result.externalProvider()).isTrue();
    }
}
