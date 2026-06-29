package com.example.monkey.user.domain;

import java.time.Duration;
import java.util.Optional;

public interface CaptchaChallengeStore {

    boolean available();

    void store(String challengeId, String code, Duration ttl);

    Optional<String> consume(String challengeId);

    static CaptchaChallengeStore unavailable() {
        return UnavailableCaptchaChallengeStore.INSTANCE;
    }

    enum UnavailableCaptchaChallengeStore implements CaptchaChallengeStore {
        INSTANCE;

        @Override
        public boolean available() {
            return false;
        }

        @Override
        public void store(String challengeId, String code, Duration ttl) {
            // No shared captcha store is configured.
        }

        @Override
        public Optional<String> consume(String challengeId) {
            return Optional.empty();
        }
    }
}
