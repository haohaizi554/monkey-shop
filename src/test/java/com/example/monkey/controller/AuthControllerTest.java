package com.example.monkey.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

import com.example.monkey.service.CaptchaService;
import com.example.monkey.service.FileService;
import com.example.monkey.service.UserService;
import jakarta.servlet.http.HttpSession;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock
    private UserService userService;

    @Mock
    private CaptchaService captchaService;

    @Mock
    private FileService fileService;

    @Mock
    private HttpSession session;

    @Test
    void resetPasswordFailsClosedUntilOtpProviderExists() {
        AuthController controller = new AuthController(userService, captchaService, fileService);

        String result = controller.resetPassword(
                Map.of(
                        "username", "alice",
                        "phone", "18888888888",
                        "newPassword", "StrongPass1!",
                        "captcha", "ABCD"),
                session);

        assertThat(result).isEqualTo(AuthController.PASSWORD_RESET_UNAVAILABLE);
        verifyNoInteractions(userService, captchaService, fileService);
    }
}
