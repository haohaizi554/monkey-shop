package com.example.monkey.user.application;

import com.example.monkey.shared.application.security.SessionTokenPair;
import com.example.monkey.shared.domain.security.JwtTokenPair;
import com.example.monkey.user.domain.SessionTokenService;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class SessionTokenApplicationService {

    private final SessionTokenService sessionTokenService;

    public SessionTokenApplicationService(SessionTokenService sessionTokenService) {
        this.sessionTokenService = sessionTokenService;
    }

    public SessionTokenPair issueTokenPair(Long userId, String role, Collection<String> authorities) {
        return fromDomain(sessionTokenService.issueTokenPair(userId, role, authorities));
    }

    public Optional<AuthenticatedRefreshToken> parseRefreshToken(String rawToken) {
        return sessionTokenService.parseRefreshToken(rawToken).map(SessionTokenApplicationService::fromDomain);
    }

    public SessionTokenPair rotateRefreshToken(
            AuthenticatedRefreshToken refreshToken, String currentRole, Collection<String> currentAuthorities) {
        return fromDomain(
                sessionTokenService.rotateRefreshToken(toDomain(refreshToken), currentRole, currentAuthorities));
    }

    public void revokeRefreshToken(AuthenticatedRefreshToken refreshToken) {
        sessionTokenService.revokeRefreshToken(toDomain(refreshToken));
    }

    public boolean revokeUserTokensForRefreshTokenReuse(String rawRefreshToken) {
        return sessionTokenService.revokeUserTokensForRefreshTokenReuse(rawRefreshToken);
    }

    private static AuthenticatedRefreshToken fromDomain(SessionTokenService.AuthenticatedRefreshToken refreshToken) {
        return new AuthenticatedRefreshToken(
                refreshToken.userId(),
                refreshToken.role(),
                refreshToken.authorities(),
                refreshToken.tokenId(),
                refreshToken.expiration(),
                refreshToken.tenantId());
    }

    private static SessionTokenPair fromDomain(JwtTokenPair pair) {
        return new SessionTokenPair(
                pair.accessToken(),
                pair.refreshToken(),
                pair.accessTokenId(),
                pair.refreshTokenId(),
                pair.accessTtlSeconds(),
                pair.refreshTtlSeconds());
    }

    private static SessionTokenService.AuthenticatedRefreshToken toDomain(AuthenticatedRefreshToken refreshToken) {
        return new SessionTokenService.AuthenticatedRefreshToken(
                refreshToken.userId(),
                refreshToken.role(),
                refreshToken.authorities(),
                refreshToken.tokenId(),
                refreshToken.expiration(),
                refreshToken.tenantId());
    }

    public record AuthenticatedRefreshToken(
            Long userId, String role, List<String> authorities, String tokenId, Instant expiration, Long tenantId) {
        public AuthenticatedRefreshToken(
                Long userId, String role, List<String> authorities, String tokenId, Instant expiration) {
            this(userId, role, authorities, tokenId, expiration, 1L);
        }

        public AuthenticatedRefreshToken {
            authorities = authorities == null ? List.of() : List.copyOf(authorities);
            tenantId = tenantId == null || tenantId <= 0 ? 1L : tenantId;
        }
    }
}
