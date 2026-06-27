package com.example.monkey.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

class CaptchaServiceTest {

    private final CaptchaService captchaService = new CaptchaService();

    @Test
    void successfulValidationConsumesCaptchaCode() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(CaptchaService.CAPTCHA_CODE_ATTRIBUTE, "ABCD");

        boolean valid = captchaService.validate(session, "abcd");

        assertThat(valid).isTrue();
        assertThat(session.getAttribute(CaptchaService.CAPTCHA_CODE_ATTRIBUTE)).isNull();
    }

    @Test
    void failedValidationConsumesCaptchaCode() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(CaptchaService.CAPTCHA_CODE_ATTRIBUTE, "ABCD");

        boolean valid = captchaService.validate(session, "WXYZ");

        assertThat(valid).isFalse();
        assertThat(session.getAttribute(CaptchaService.CAPTCHA_CODE_ATTRIBUTE)).isNull();
    }
}
