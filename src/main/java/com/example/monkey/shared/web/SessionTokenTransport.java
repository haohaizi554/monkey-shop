package com.example.monkey.shared.web;

import com.example.monkey.domain.user.SessionTokenService.JwtTokenPair;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;

public interface SessionTokenTransport {

    void applyTokenCookies(HttpServletResponse response, JwtTokenPair pair);

    void clearTokenCookies(HttpServletResponse response);

    Optional<String> resolveAccessToken(HttpServletRequest request);

    String resolveRefreshToken(HttpServletRequest request);

    void revokeTokens(HttpServletRequest request, HttpServletResponse response);

    void revokeUserTokens(Long userId, HttpServletResponse response);
}
