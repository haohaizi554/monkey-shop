package com.example.monkey.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.shared.application.security.SessionTokenPair;
import com.example.monkey.shared.domain.security.JwtTokenPair;
import com.example.monkey.user.domain.SessionTokenService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SessionTokenApplicationServiceTest {

    private final SessionTokenService sessionTokenService = org.mockito.Mockito.mock(SessionTokenService.class);
    private final SessionTokenApplicationService service = new SessionTokenApplicationService(sessionTokenService);

    @Test
    void issuesTokenPairsThroughDomainPort() {
        JwtTokenPair pair = new JwtTokenPair("access", "refresh", "access-id", "refresh-id", 900, 604800);
        when(sessionTokenService.issueTokenPair(7L, "USER", List.of("ROLE_USER")))
                .thenReturn(pair);

        SessionTokenPair result = service.issueTokenPair(7L, "USER", List.of("ROLE_USER"));

        assertThat(result.accessToken()).isEqualTo("access");
        assertThat(result.refreshToken()).isEqualTo("refresh");
        assertThat(result.accessTokenId()).isEqualTo("access-id");
        assertThat(result.refreshTokenId()).isEqualTo("refresh-id");
        assertThat(result.accessTtlSeconds()).isEqualTo(900);
        assertThat(result.refreshTtlSeconds()).isEqualTo(604800);

        verify(sessionTokenService).issueTokenPair(7L, "USER", List.of("ROLE_USER"));
    }

    @Test
    void mapsParsedRefreshTokenToApplicationRecordAndRotatesBackThroughDomainPort() {
        Instant expiration = Instant.now().plusSeconds(60);
        SessionTokenService.AuthenticatedRefreshToken domainToken = new SessionTokenService.AuthenticatedRefreshToken(
                7L, "USER", List.of("ROLE_USER"), "refresh-id", expiration);
        JwtTokenPair rotated = new JwtTokenPair("access", "refresh", "access-id", "new-refresh-id", 900, 604800);
        when(sessionTokenService.parseRefreshToken("raw-refresh")).thenReturn(Optional.of(domainToken));
        when(sessionTokenService.rotateRefreshToken(domainToken, "USER", List.of("ROLE_USER")))
                .thenReturn(rotated);

        SessionTokenApplicationService.AuthenticatedRefreshToken token =
                service.parseRefreshToken("raw-refresh").orElseThrow();
        SessionTokenPair result = service.rotateRefreshToken(token, "USER", List.of("ROLE_USER"));

        assertThat(token.userId()).isEqualTo(7L);
        assertThat(token.authorities()).containsExactly("ROLE_USER");
        assertThat(result.refreshTokenId()).isEqualTo("new-refresh-id");
        verify(sessionTokenService).rotateRefreshToken(domainToken, "USER", List.of("ROLE_USER"));
    }

    @Test
    void revokesRefreshTokensAndDetectsReuseThroughDomainPort() {
        Instant expiration = Instant.now().plusSeconds(60);
        SessionTokenApplicationService.AuthenticatedRefreshToken token =
                new SessionTokenApplicationService.AuthenticatedRefreshToken(
                        7L, "USER", List.of("ROLE_USER"), "refresh-id", expiration);
        when(sessionTokenService.revokeUserTokensForRefreshTokenReuse("raw-refresh"))
                .thenReturn(true);

        service.revokeRefreshToken(token);
        assertThat(service.revokeUserTokensForRefreshTokenReuse("raw-refresh")).isTrue();

        verify(sessionTokenService)
                .revokeRefreshToken(new SessionTokenService.AuthenticatedRefreshToken(
                        7L, "USER", List.of("ROLE_USER"), "refresh-id", expiration));
        verify(sessionTokenService).revokeUserTokensForRefreshTokenReuse("raw-refresh");
    }

    @Test
    void mapsRecoveredRefreshTokenRotationThroughDomainPort() {
        JwtTokenPair pair = new JwtTokenPair("access", "refresh", "access-id", "refresh-id", 900, 604800);
        SessionTokenService.RecoveredRefreshToken domainRecovery =
                new SessionTokenService.RecoveredRefreshToken(7L, "USER", 3L, pair);
        when(sessionTokenService.recoverRefreshTokenRotation("old-refresh")).thenReturn(Optional.of(domainRecovery));

        SessionTokenApplicationService.RecoveredRefreshToken recovered =
                service.recoverRefreshTokenRotation("old-refresh").orElseThrow();

        assertThat(recovered.userId()).isEqualTo(7L);
        assertThat(recovered.role()).isEqualTo("USER");
        assertThat(recovered.tenantId()).isEqualTo(3L);
        assertThat(recovered.tokenPair().refreshToken()).isEqualTo("refresh");
        verify(sessionTokenService).recoverRefreshTokenRotation("old-refresh");
    }
}
