package com.example.monkey.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.monkey.shared.application.observability.AuditService;
import com.example.monkey.shared.application.security.SessionTokenPair;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.user.application.RefreshTokenApplicationService.RefreshTokenFailure;
import com.example.monkey.user.application.SessionTokenApplicationService.AuthenticatedRefreshToken;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RefreshTokenApplicationServiceTest {

    @Mock
    private AuthenticationApplicationService authenticationService;

    @Mock
    private SessionTokenApplicationService tokenService;

    @Mock
    private AuditService auditService;

    private RefreshTokenApplicationService refreshTokenService;

    @BeforeEach
    void setUp() {
        refreshTokenService = new RefreshTokenApplicationService(authenticationService, tokenService, auditService);
    }

    @Test
    void refreshRotatesTokenPairWithCurrentPrincipalAuthorities() {
        AuthenticatedRefreshToken refreshToken = refreshToken(7L, "USER");
        AuthenticatedUserPrincipal currentPrincipal =
                new AuthenticatedUserPrincipal(7L, "USER", List.of("ROLE_USER", "ORDER_READ_OWN"), false);
        SessionTokenPair tokenPair =
                new SessionTokenPair("access-token", "refresh-token", "access-id", "new-refresh-id", 900, 604800);
        when(tokenService.parseRefreshToken("old-refresh-token")).thenReturn(Optional.of(refreshToken));
        when(authenticationService.currentPrincipal(7L, 1L)).thenReturn(Optional.of(currentPrincipal));
        when(tokenService.rotateRefreshToken(refreshToken, "USER", currentPrincipal.authorities()))
                .thenReturn(tokenPair);

        var result = refreshTokenService.refresh("old-refresh-token", "203.0.113.10");

        assertThat(result.tokenPair()).isSameAs(tokenPair);
        verify(auditService)
                .record(
                        AuditService.REFRESH_TOKEN_SUCCESS,
                        AuditService.OUTCOME_SUCCESS,
                        7L,
                        "USER",
                        null,
                        "203.0.113.10",
                        null);
    }

    @Test
    void missingRefreshTokenAuditsFailureWithoutCookieClearSignal() {
        assertThatExceptionOfType(RefreshTokenFailure.class)
                .isThrownBy(() -> refreshTokenService.refresh(" ", "203.0.113.13"))
                .withMessage(RefreshTokenApplicationService.REFRESH_TOKEN_NOT_PROVIDED)
                .satisfies(exception -> {
                    assertThat(exception.errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
                    assertThat(exception.clearTokenCookies()).isFalse();
                });

        verify(auditService)
                .record(
                        AuditService.REFRESH_TOKEN_FAILURE,
                        AuditService.OUTCOME_FAILURE,
                        null,
                        null,
                        null,
                        "203.0.113.13",
                        "missing_refresh_token");
        verifyNoInteractions(authenticationService, tokenService);
    }

    @Test
    void invalidRefreshTokenAuditsFailureAndSignalsCookieClear() {
        when(tokenService.parseRefreshToken("tampered-refresh-token")).thenReturn(Optional.empty());
        when(tokenService.revokeUserTokensForRefreshTokenReuse("tampered-refresh-token"))
                .thenReturn(false);

        assertThatExceptionOfType(RefreshTokenFailure.class)
                .isThrownBy(() -> refreshTokenService.refresh("tampered-refresh-token", "203.0.113.14"))
                .withMessage(RefreshTokenApplicationService.REFRESH_TOKEN_INVALID)
                .satisfies(exception -> {
                    assertThat(exception.errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
                    assertThat(exception.clearTokenCookies()).isTrue();
                });

        verify(auditService)
                .record(
                        AuditService.REFRESH_TOKEN_FAILURE,
                        AuditService.OUTCOME_FAILURE,
                        null,
                        null,
                        null,
                        "203.0.113.14",
                        "invalid_refresh_token");
        verifyNoInteractions(authenticationService);
    }

    @Test
    void replayedRefreshTokenRevokesSubjectAuditsReplayAndSignalsCookieClear() {
        when(tokenService.parseRefreshToken("replayed-refresh-token")).thenReturn(Optional.empty());
        when(tokenService.revokeUserTokensForRefreshTokenReuse("replayed-refresh-token"))
                .thenReturn(true);

        assertThatExceptionOfType(RefreshTokenFailure.class)
                .isThrownBy(() -> refreshTokenService.refresh("replayed-refresh-token", "203.0.113.12"))
                .withMessage(RefreshTokenApplicationService.REFRESH_TOKEN_INVALID)
                .satisfies(exception -> {
                    assertThat(exception.errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
                    assertThat(exception.clearTokenCookies()).isTrue();
                });

        verify(auditService)
                .record(
                        AuditService.REFRESH_TOKEN_REPLAY,
                        AuditService.OUTCOME_DENIED,
                        null,
                        null,
                        null,
                        "203.0.113.12",
                        "replay_detected");
        verifyNoInteractions(authenticationService);
    }

    @Test
    void rejectedCurrentPrincipalRevokesRefreshTokenAndSignalsCookieClear() {
        AuthenticatedRefreshToken refreshToken = refreshToken(7L, "USER");
        when(tokenService.parseRefreshToken("old-refresh-token")).thenReturn(Optional.of(refreshToken));
        when(authenticationService.currentPrincipal(7L, 1L)).thenReturn(Optional.empty());

        assertThatExceptionOfType(RefreshTokenFailure.class)
                .isThrownBy(() -> refreshTokenService.refresh("old-refresh-token", "203.0.113.11"))
                .withMessage(RefreshTokenApplicationService.REFRESH_TOKEN_INVALID)
                .satisfies(exception -> {
                    assertThat(exception.errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
                    assertThat(exception.clearTokenCookies()).isTrue();
                });

        verify(tokenService).revokeRefreshToken(refreshToken);
        verify(tokenService, never()).rotateRefreshToken(any(AuthenticatedRefreshToken.class), anyString(), any());
        verify(auditService)
                .record(
                        AuditService.REFRESH_TOKEN_FAILURE,
                        AuditService.OUTCOME_DENIED,
                        7L,
                        "USER",
                        null,
                        "203.0.113.11",
                        "principal_rejected");
    }

    private static AuthenticatedRefreshToken refreshToken(Long userId, String role) {
        return new AuthenticatedRefreshToken(
                userId,
                role,
                List.of("ROLE_" + role),
                "refresh-id",
                Instant.now().plusSeconds(60));
    }
}
