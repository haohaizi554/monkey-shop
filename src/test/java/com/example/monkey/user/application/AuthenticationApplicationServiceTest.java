package com.example.monkey.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.user.domain.AuthPrincipal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AuthenticationApplicationServiceTest {

    private final UserService userService = org.mockito.Mockito.mock(UserService.class);
    private final AuthenticationApplicationService service = new AuthenticationApplicationService(userService);

    @Test
    void mapsAuthenticatedPrincipalToApplicationPrincipal() {
        when(userService.authenticate("alice", "StrongPass1!"))
                .thenReturn(new AuthPrincipal(7L, "USER", List.of("ROLE_USER"), true));

        AuthenticatedUserPrincipal principal = service.authenticate("alice", "StrongPass1!");

        assertThat(principal.userId()).isEqualTo(7L);
        assertThat(principal.role()).isEqualTo("USER");
        assertThat(principal.authorities()).containsExactly("ROLE_USER");
        assertThat(principal.passwordChangeRequired()).isTrue();
        assertThat(principal.tenantId()).isEqualTo(1L);
    }

    @Test
    void returnsNullWhenAuthenticationFails() {
        when(userService.authenticate("alice", "bad")).thenReturn(null);

        assertThat(service.authenticate("alice", "bad")).isNull();
    }

    @Test
    void mapsCurrentPrincipalAndDelegatesTotpVerification() {
        when(userService.currentPrincipal(7L))
                .thenReturn(Optional.of(new AuthPrincipal(7L, "ADMIN", List.of("ROLE_ADMIN"), false)));
        when(userService.verifyAdminTotp(7L, "654321")).thenReturn(true);

        Optional<AuthenticatedUserPrincipal> principal = service.currentPrincipal(7L);

        assertThat(principal)
                .hasValueSatisfying(value -> assertThat(value.role()).isEqualTo("ADMIN"));
        assertThat(service.verifyAdminTotp(7L, "654321")).isTrue();
        verify(userService).verifyAdminTotp(7L, "654321");
    }

    @Test
    void mapsCurrentPrincipalForTenantScopedLookup() {
        when(userService.currentPrincipal(7L, 300L))
                .thenReturn(Optional.of(new AuthPrincipal(7L, "USER", List.of("ROLE_USER"), false, 300L)));

        Optional<AuthenticatedUserPrincipal> principal = service.currentPrincipal(7L, 300L);

        assertThat(principal)
                .hasValueSatisfying(value -> assertThat(value.tenantId()).isEqualTo(300L));
    }
}
