package com.example.monkey.shared.interfaces.web;

import com.example.monkey.shared.application.security.SessionTokenPair;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;

public interface SessionTokenTransport {

    void applyTokenCookies(HttpServletResponse response, SessionTokenPair pair);

    void clearTokenCookies(HttpServletResponse response);

    Optional<String> resolveAccessToken(HttpServletRequest request);

    String resolveRefreshToken(HttpServletRequest request);

    void revokeTokens(HttpServletRequest request, HttpServletResponse response);

    void revokeUserTokens(Long userId, HttpServletResponse response);
}
