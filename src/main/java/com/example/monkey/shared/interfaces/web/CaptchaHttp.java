package com.example.monkey.shared.interfaces.web;

import com.example.monkey.shared.application.security.CaptchaChallengeResult;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.util.StringUtils;

public final class CaptchaHttp {

    public static final String CAPTCHA_ID_COOKIE = "CAPTCHA_ID";
    private static final String CAPTCHA_PROVIDER_HEADER = "X-Captcha-Provider";
    private static final String TURNSTILE_SITE_KEY_HEADER = "X-Turnstile-Site-Key";

    private CaptchaHttp() {}

    public static void write(CaptchaChallengeResult challenge, HttpServletResponse response) throws IOException {
        if (challenge.externalProvider()) {
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
            response.setHeader(CAPTCHA_PROVIDER_HEADER, challenge.provider());
            if (StringUtils.hasText(challenge.siteKey())) {
                response.setHeader(TURNSTILE_SITE_KEY_HEADER, challenge.siteKey());
            }
            return;
        }
        challenge.challengeId().ifPresent(challengeId -> addChallengeCookie(response, challenge, challengeId));
        response.setHeader("Pragma", "No-cache");
        response.setHeader("Cache-Control", "no-cache");
        response.setDateHeader("Expires", 0);
        response.setContentType(challenge.contentType().orElse("image/jpeg"));
        response.getOutputStream().write(challenge.content());
    }

    public static String challengeId(HttpServletRequest request) {
        return resolveCookie(request, CAPTCHA_ID_COOKIE).orElse(null);
    }

    private static void addChallengeCookie(
            HttpServletResponse response, CaptchaChallengeResult challenge, String challengeId) {
        ResponseCookie cookie = ResponseCookie.from(CAPTCHA_ID_COOKIE, challengeId)
                .httpOnly(true)
                .secure(challenge.cookieSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(challenge.ttl())
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private static Optional<String> resolveCookie(HttpServletRequest request, String name) {
        if (request == null || request.getCookies() == null) {
            return Optional.empty();
        }
        for (Cookie cookie : request.getCookies()) {
            if (name.equals(cookie.getName()) && StringUtils.hasText(cookie.getValue())) {
                return Optional.of(cookie.getValue());
            }
        }
        return Optional.empty();
    }
}
