package com.example.monkey.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.user.domain.LoginAttemptPolicy;
import com.example.monkey.user.domain.LoginAttemptState;
import org.junit.jupiter.api.Test;

class LoginAttemptApplicationServiceTest {

    private final LoginAttemptPolicy loginAttemptPolicy = org.mockito.Mockito.mock(LoginAttemptPolicy.class);
    private final LoginAttemptApplicationService service = new LoginAttemptApplicationService(loginAttemptPolicy);

    @Test
    void delegatesLoginAttemptPolicyOperations() {
        LoginAttemptState state = LoginAttemptState.allowed(true);
        when(loginAttemptPolicy.evaluate("alice", "127.0.0.1")).thenReturn(state);

        assertThat(service.evaluate("alice", "127.0.0.1")).isEqualTo(state);
        service.recordFailure("alice", "127.0.0.1");
        service.recordSuccess("alice", "127.0.0.1");

        verify(loginAttemptPolicy).evaluate("alice", "127.0.0.1");
        verify(loginAttemptPolicy).recordFailure("alice", "127.0.0.1");
        verify(loginAttemptPolicy).recordSuccess("alice", "127.0.0.1");
    }
}
