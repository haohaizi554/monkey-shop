package com.example.monkey.domain.user;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SessionTokenService {

    JwtTokenPair issueTokenPair(Long userId, String role, Collection<String> authorities);

    Optional<AuthenticatedAccessToken> parseAccessToken(String rawToken);

    Optional<AuthenticatedRefreshToken> parseRefreshToken(String rawToken);

    JwtTokenPair rotateRefreshToken(
            AuthenticatedRefreshToken refreshToken, String currentRole, Collection<String> currentAuthorities);

    void revokeRefreshToken(AuthenticatedRefreshToken refreshToken);

    boolean revokeUserTokensForRefreshTokenReuse(String rawRefreshToken);

    void revokeUserTokens(Long userId);

    void revokeAccessToken(String rawAccessToken);

    record JwtTokenPair(
            String accessToken,
            String refreshToken,
            String accessTokenId,
            String refreshTokenId,
            long accessTtlSeconds,
            long refreshTtlSeconds) {}

    record AuthenticatedAccessToken(
            Long userId, String role, List<String> authorities, String tokenId, Instant expiration) {}

    record AuthenticatedRefreshToken(
            Long userId, String role, List<String> authorities, String tokenId, Instant expiration) {}
}
