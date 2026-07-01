package com.example.monkey.shared.domain.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class CaptchaChallengeTest {

    @Test
    void localChallengeExposesRenderingPropertiesAndDefensivelyCopiesContent() {
        byte[] originalContent = new byte[] {1, 2, 3};

        CaptchaChallenge challenge =
                CaptchaChallenge.local("challenge-id", Duration.ofSeconds(45), true, "image/png", originalContent);
        originalContent[0] = 9;
        byte[] returnedContent = challenge.content();
        returnedContent[1] = 9;

        assertThat(challenge.provider()).isEqualTo("local");
        assertThat(challenge.siteKey()).isEmpty();
        assertThat(challenge.challengeId()).hasValue("challenge-id");
        assertThat(challenge.ttl()).isEqualTo(Duration.ofSeconds(45));
        assertThat(challenge.cookieSecure()).isTrue();
        assertThat(challenge.contentType()).hasValue("image/png");
        assertThat(challenge.content()).containsExactly(1, 2, 3);
        assertThat(challenge.externalProvider()).isFalse();
    }

    @Test
    void externalChallengeExposesProviderMetadataWithoutBodyOrCookieState() {
        CaptchaChallenge challenge = CaptchaChallenge.external("turnstile", "site-key");

        assertThat(challenge.provider()).isEqualTo("turnstile");
        assertThat(challenge.siteKey()).isEqualTo("site-key");
        assertThat(challenge.challengeId()).isEmpty();
        assertThat(challenge.ttl()).isZero();
        assertThat(challenge.cookieSecure()).isFalse();
        assertThat(challenge.contentType()).isEmpty();
        assertThat(challenge.content()).isEmpty();
        assertThat(challenge.externalProvider()).isTrue();
    }

    @Test
    void nullContentIsNormalizedToAnEmptyBody() {
        CaptchaChallenge challenge =
                CaptchaChallenge.local("challenge-id", Duration.ofMinutes(1), false, "image/jpeg", null);

        assertThat(challenge.challengeId()).hasValue("challenge-id");
        assertThat(challenge.content()).isEmpty();
        assertThat(challenge.externalProvider()).isTrue();
    }
}
