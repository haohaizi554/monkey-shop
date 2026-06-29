package com.example.monkey.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.monkey.domain.user.CaptchaChallenge;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CaptchaHttpTest {

    @Test
    void writesLocalChallengeCookieAndImageResponse() throws Exception {
        CaptchaChallenge challenge =
                CaptchaChallenge.local("challenge-id", Duration.ofMinutes(5), true, "image/jpeg", new byte[] {1, 2, 3});
        MockHttpServletResponse response = new MockHttpServletResponse();

        CaptchaHttp.write(challenge, response);

        assertThat(response.getHeader(HttpHeaders.SET_COOKIE))
                .contains(CaptchaHttp.CAPTCHA_ID_COOKIE + "=challenge-id")
                .contains("Max-Age=300")
                .contains("Path=/")
                .contains("HttpOnly")
                .contains("Secure")
                .contains("SameSite=Lax");
        assertThat(response.getContentType()).isEqualTo("image/jpeg");
        assertThat(response.getContentAsByteArray()).containsExactly(1, 2, 3);
    }

    @Test
    void writesExternalProviderMetadataWithoutBody() throws Exception {
        CaptchaChallenge challenge = CaptchaChallenge.external("turnstile", "site-key");
        MockHttpServletResponse response = new MockHttpServletResponse();

        CaptchaHttp.write(challenge, response);

        assertThat(response.getStatus()).isEqualTo(204);
        assertThat(response.getHeader("X-Captcha-Provider")).isEqualTo("turnstile");
        assertThat(response.getHeader("X-Turnstile-Site-Key")).isEqualTo("site-key");
        assertThat(response.getContentAsByteArray()).isEmpty();
    }

    @Test
    void resolvesNonBlankChallengeCookie() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(CaptchaHttp.CAPTCHA_ID_COOKIE, "challenge-id"));
        MockHttpServletRequest blankRequest = new MockHttpServletRequest();
        blankRequest.setCookies(new Cookie(CaptchaHttp.CAPTCHA_ID_COOKIE, " "));

        assertThat(CaptchaHttp.challengeId(request)).isEqualTo("challenge-id");
        assertThat(CaptchaHttp.challengeId(blankRequest)).isNull();
        assertThat(CaptchaHttp.challengeId(new MockHttpServletRequest())).isNull();
        assertThat(CaptchaHttp.challengeId(null)).isNull();
    }
}
