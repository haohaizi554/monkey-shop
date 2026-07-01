package com.example.monkey.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.user.domain.PasswordResetChallengeService;
import org.junit.jupiter.api.Test;

class PasswordResetChallengeApplicationServiceTest {

    private final PasswordResetChallengeService challengeService =
            org.mockito.Mockito.mock(PasswordResetChallengeService.class);
    private final PasswordResetChallengeApplicationService service =
            new PasswordResetChallengeApplicationService(challengeService);

    @Test
    void delegatesPasswordResetChallengeOperations() {
        when(challengeService.consumeResetOtp("alice", "18888888888", "654321")).thenReturn(true);
        when(challengeService.consumeResetChallenge(
                        "alice", "18888888888", "alice@example.com", "654321", "email-token"))
                .thenReturn(true);

        service.issueResetChallenge("alice", "18888888888", "alice@example.com", true);
        assertThat(service.consumeResetOtp("alice", "18888888888", "654321")).isTrue();
        assertThat(service.consumeResetChallenge("alice", "18888888888", "alice@example.com", "654321", "email-token"))
                .isTrue();

        verify(challengeService).issueResetChallenge("alice", "18888888888", "alice@example.com", true);
        verify(challengeService).consumeResetOtp("alice", "18888888888", "654321");
        verify(challengeService)
                .consumeResetChallenge("alice", "18888888888", "alice@example.com", "654321", "email-token");
    }
}
