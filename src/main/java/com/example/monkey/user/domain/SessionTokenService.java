package com.example.monkey.user.domain;

import com.example.monkey.shared.domain.security.JwtTokenPair;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SessionTokenService {

    JwtTokenPair issueTokenPair(Long userId, String role, Collection<String> authorities);

    JwtTokenPair issueTokenPair(Long userId, String role, Collection<String> authorities, Long tenantId);

    Optional<AuthenticatedAccessToken> parseAccessToken(String rawToken);

    Optional<AuthenticatedRefreshToken> parseRefreshToken(String rawToken);

    JwtTokenPair rotateRefreshToken(
            AuthenticatedRefreshToken refreshToken, String currentRole, Collection<String> currentAuthorities);

    void revokeRefreshToken(AuthenticatedRefreshToken refreshToken);

    boolean revokeUserTokensForRefreshTokenReuse(String rawRefreshToken);

    void revokeUserTokens(Long userId);

    void revokeAccessToken(String rawAccessToken);

    record AuthenticatedAccessToken(
            Long userId, String role, List<String> authorities, String tokenId, Instant expiration, Long tenantId) {

        public AuthenticatedAccessToken(
                Long userId, String role, List<String> authorities, String tokenId, Instant expiration) {
            this(userId, role, authorities, tokenId, expiration, 1L);
        }
    }

    record AuthenticatedRefreshToken(
            Long userId, String role, List<String> authorities, String tokenId, Instant expiration, Long tenantId) {

        public AuthenticatedRefreshToken(
                Long userId, String role, List<String> authorities, String tokenId, Instant expiration) {
            this(userId, role, authorities, tokenId, expiration, 1L);
        }
    }
}
