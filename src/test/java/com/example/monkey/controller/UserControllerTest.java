package com.example.monkey.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.monkey.domain.user.SessionUser;
import com.example.monkey.dto.PasswordChangeRequestDto;
import com.example.monkey.dto.UserAvatarRequestDto;
import com.example.monkey.dto.UserProfileResponseDto;
import com.example.monkey.service.AuditService;
import com.example.monkey.service.CaptchaService;
import com.example.monkey.service.UserService;
import com.example.monkey.shared.api.ErrorCode;
import com.example.monkey.shared.api.Result;
import com.example.monkey.shared.exception.BusinessException;
import com.example.monkey.shared.web.CaptchaHttp;
import com.example.monkey.shared.web.SessionTokenTransport;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
class UserControllerTest {
    private static final String CAPTCHA_CHALLENGE_ID = "challenge-id";

    @Mock
    private UserService userService;

    @Mock
    private CaptchaService captchaService;

    @Mock
    private AuditService auditService;

    @Mock
    private SessionTokenTransport tokenTransport;

    private UserController controller;

    @BeforeEach
    void setUp() {
        controller = new UserController(userService, captchaService, auditService, tokenTransport);
    }

    @Test
    void getCurrentUserWrapsProfileInResultEnvelope() {
        SessionUser principal = new SessionUser(7L, "USER");
        UserProfileResponseDto profile =
                new UserProfileResponseDto(true, "USER", "alice", "/avatar.png", "188****8888", false);
        when(userService.getUserInfo(principal, false)).thenReturn(profile);

        Result<UserProfileResponseDto> result = controller.getCurrentUser(principal);

        assertThat(result.code()).isEqualTo("OK");
        assertThat(result.data()).isSameAs(profile);
        verify(userService).getUserInfo(principal, false);
    }

    @Test
    void getProfileWrapsFullProfileInResultEnvelope() {
        SessionUser principal = new SessionUser(7L, "USER");
        UserProfileResponseDto profile =
                new UserProfileResponseDto(true, "USER", "alice", "/avatar.png", "188****8888", false);
        when(userService.getUserInfo(principal, true)).thenReturn(profile);

        Result<UserProfileResponseDto> result = controller.getProfile(principal);

        assertThat(result.code()).isEqualTo("OK");
        assertThat(result.data()).isSameAs(profile);
        verify(userService).getUserInfo(principal, true);
    }

    @Test
    void updateAvatarWrapsSuccessInResultEnvelope() {
        SessionUser principal = new SessionUser(7L, "USER");
        Result<Void> result = controller.updateAvatar(new UserAvatarRequestDto("/avatar/new.png"), principal);

        assertThat(result.code()).isEqualTo("OK");
        assertThat(result.data()).isNull();
        verify(userService).updateAvatar(7L, "/avatar/new.png");
    }

    @Test
    void updateAvatarMapsLegacyFailureToBusinessException() {
        SessionUser principal = new SessionUser(7L, "USER");
        doThrow(new BusinessException(ErrorCode.NOT_FOUND, "user not found"))
                .when(userService)
                .updateAvatar(7L, "/avatar/new.png");

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> controller.updateAvatar(new UserAvatarRequestDto("/avatar/new.png"), principal))
                .withMessage("user not found")
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void updateAvatarRejectsMissingAuthenticatedUser() {
        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> controller.updateAvatar(new UserAvatarRequestDto("/avatar/new.png"), null))
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));

        verifyNoInteractions(userService);
    }

    @Test
    void updatePasswordAuditsCaptchaFailure() {
        MockHttpServletRequest request = requestWithCaptcha();
        when(captchaService.validate(CAPTCHA_CHALLENGE_ID, "bad", "change-password", "127.0.0.1"))
                .thenReturn(false);

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> controller.updatePassword(
                        new PasswordChangeRequestDto("18888888888", "StrongPass1!", "bad"),
                        request,
                        new MockHttpServletResponse(),
                        new SessionUser(7L, "USER")))
                .withMessage("captcha incorrect")
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        verify(auditService)
                .record(
                        AuditService.PASSWORD_CHANGE_FAILURE,
                        AuditService.OUTCOME_FAILURE,
                        7L,
                        "USER",
                        null,
                        "127.0.0.1",
                        "captcha_invalid");
        verifyNoInteractions(tokenTransport);
    }

    @Test
    void updatePasswordAuditsSuccessAndInvalidatesSession() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.8");
        request.addHeader("X-Forwarded-For", "203.0.113.10, 10.0.0.8");
        request.setCookies(new Cookie(CaptchaHttp.CAPTCHA_ID_COOKIE, CAPTCHA_CHALLENGE_ID));
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(captchaService.validate(CAPTCHA_CHALLENGE_ID, "1234", "change-password", "203.0.113.10"))
                .thenReturn(true);
        Result<Void> result = controller.updatePassword(
                new PasswordChangeRequestDto("18888888888", "StrongPass1!", "1234"),
                request,
                response,
                new SessionUser(7L, "USER"));

        assertThat(result.code()).isEqualTo("OK");
        assertThat(result.data()).isNull();
        verify(tokenTransport).revokeUserTokens(7L, response);
        verify(auditService)
                .record(
                        AuditService.PASSWORD_CHANGE_SUCCESS,
                        AuditService.OUTCOME_SUCCESS,
                        7L,
                        "USER",
                        null,
                        "203.0.113.10",
                        null);
    }

    @Test
    void updatePasswordRejectsMissingAuthenticatedUserBeforeCaptchaValidation() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> controller.updatePassword(
                        new PasswordChangeRequestDto("18888888888", "StrongPass1!", "1234"),
                        request,
                        new MockHttpServletResponse(),
                        null))
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));

        verifyNoInteractions(captchaService, auditService, tokenTransport);
    }

    private static MockHttpServletRequest requestWithCaptcha() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.setCookies(new Cookie(CaptchaHttp.CAPTCHA_ID_COOKIE, CAPTCHA_CHALLENGE_ID));
        return request;
    }
}
