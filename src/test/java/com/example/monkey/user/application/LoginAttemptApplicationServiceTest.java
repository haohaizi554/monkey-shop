package com.example.monkey.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.user.domain.LoginAttemptPolicy;
import org.junit.jupiter.api.Test;

class LoginAttemptApplicationServiceTest {

    private final LoginAttemptPolicy loginAttemptPolicy = org.mockito.Mockito.mock(LoginAttemptPolicy.class);
    private final LoginAttemptApplicationService service = new LoginAttemptApplicationService(loginAttemptPolicy);

    @Test
    void delegatesLoginAttemptPolicyOperations() {
        when(loginAttemptPolicy.requiresCaptcha("alice", "127.0.0.1")).thenReturn(true);

        service.enforceAllowed("alice", "127.0.0.1");
        assertThat(service.requiresCaptcha("alice", "127.0.0.1")).isTrue();
        service.recordFailure("alice", "127.0.0.1");
        service.recordSuccess("alice", "127.0.0.1");

        verify(loginAttemptPolicy).enforceAllowed("alice", "127.0.0.1");
        verify(loginAttemptPolicy).requiresCaptcha("alice", "127.0.0.1");
        verify(loginAttemptPolicy).recordFailure("alice", "127.0.0.1");
        verify(loginAttemptPolicy).recordSuccess("alice", "127.0.0.1");
    }
}
