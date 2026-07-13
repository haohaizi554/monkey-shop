package com.example.monkey.user.interfaces;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.shared.interfaces.dto.Result;
import com.example.monkey.shared.interfaces.web.CaptchaHttp;
import com.example.monkey.shared.interfaces.web.SessionTokenTransport;
import com.example.monkey.user.application.CaptchaService;
import com.example.monkey.user.application.PasswordChangeApplicationService;
import com.example.monkey.user.application.PasswordChangeApplicationService.PasswordChangeResult;
import com.example.monkey.user.application.UserProfileApplicationService;
import com.example.monkey.user.application.dto.UserProfileResponseDto;
import com.example.monkey.user.interfaces.dto.PasswordChangeRequestDto;
import com.example.monkey.user.interfaces.dto.UserAvatarRequestDto;
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
    private UserProfileApplicationService userProfileService;

    @Mock
    private CaptchaService captchaService;

    @Mock
    private PasswordChangeApplicationService passwordChangeService;

    @Mock
    private SessionTokenTransport tokenTransport;

    private UserController controller;

    @BeforeEach
    void setUp() {
        controller = new UserController(userProfileService, captchaService, passwordChangeService, tokenTransport);
    }

    @Test
    void getCurrentUserWrapsProfileInResultEnvelope() {
        SessionUser principal = new SessionUser(7L, "USER");
        UserProfileResponseDto profile =
                new UserProfileResponseDto(true, "USER", "alice", "/avatar.png", "188****8888", false);
        when(userProfileService.currentUser(principal)).thenReturn(profile);

        Result<UserProfileResponseDto> result = controller.getCurrentUser(principal);

        assertThat(result.code()).isEqualTo("OK");
        assertThat(result.data()).isSameAs(profile);
        verify(userProfileService).currentUser(principal);
    }

    @Test
    void getProfileWrapsFullProfileInResultEnvelope() {
        SessionUser principal = new SessionUser(7L, "USER");
        UserProfileResponseDto profile =
                new UserProfileResponseDto(true, "USER", "alice", "/avatar.png", "188****8888", false);
        when(userProfileService.profile(principal)).thenReturn(profile);

        Result<UserProfileResponseDto> result = controller.getProfile(principal);

        assertThat(result.code()).isEqualTo("OK");
        assertThat(result.data()).isSameAs(profile);
        verify(userProfileService).profile(principal);
    }

    @Test
    void updateAvatarWrapsSuccessInResultEnvelope() {
        SessionUser principal = new SessionUser(7L, "USER");
        Result<Void> result = controller.updateAvatar(new UserAvatarRequestDto("/avatar/new.png"), principal);

        assertThat(result.code()).isEqualTo("OK");
        assertThat(result.data()).isNull();
        verify(userProfileService).updateAvatar(principal, "/avatar/new.png");
    }

    @Test
    void updateAvatarPropagatesApplicationFailure() {
        SessionUser principal = new SessionUser(7L, "USER");
        doThrow(new BusinessException(ErrorCode.NOT_FOUND, "user not found"))
                .when(userProfileService)
                .updateAvatar(principal, "/avatar/new.png");

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> controller.updateAvatar(new UserAvatarRequestDto("/avatar/new.png"), principal))
                .withMessage("user not found")
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void updateAvatarPropagatesMissingAuthenticatedUserFromApplicationService() {
        doThrow(new BusinessException(ErrorCode.UNAUTHORIZED, "login required"))
                .when(userProfileService)
                .updateAvatar(null, "/avatar/new.png");

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> controller.updateAvatar(new UserAvatarRequestDto("/avatar/new.png"), null))
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));

        verify(userProfileService).updateAvatar(null, "/avatar/new.png");
    }

    @Test
    void updatePasswordDelegatesChangeAndInvalidatesSession() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.10");
        request.setCookies(new Cookie(CaptchaHttp.CAPTCHA_ID_COOKIE, CAPTCHA_CHALLENGE_ID));
        MockHttpServletResponse response = new MockHttpServletResponse();
        SessionUser currentUser = new SessionUser(7L, "USER");
        when(passwordChangeService.changePassword(
                        currentUser, CAPTCHA_CHALLENGE_ID, "1234", "18888888888", "StrongPass1!", "203.0.113.10"))
                .thenReturn(new PasswordChangeResult(7L));

        Result<Void> result = controller.updatePassword(
                new PasswordChangeRequestDto("18888888888", "StrongPass1!", "1234"), request, response, currentUser);

        assertThat(result.code()).isEqualTo("OK");
        assertThat(result.data()).isNull();
        verify(passwordChangeService)
                .changePassword(
                        currentUser, CAPTCHA_CHALLENGE_ID, "1234", "18888888888", "StrongPass1!", "203.0.113.10");
        verify(tokenTransport).revokeUserTokens(7L, response);
    }

    @Test
    void updatePasswordPropagatesApplicationFailureWithoutInvalidatingSession() {
        MockHttpServletRequest request = requestWithCaptcha();
        MockHttpServletResponse response = new MockHttpServletResponse();
        SessionUser currentUser = new SessionUser(7L, "USER");
        doThrow(new BusinessException(ErrorCode.VALIDATION_ERROR, "captcha incorrect"))
                .when(passwordChangeService)
                .changePassword(currentUser, CAPTCHA_CHALLENGE_ID, "bad", "18888888888", "StrongPass1!", "127.0.0.1");

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> controller.updatePassword(
                        new PasswordChangeRequestDto("18888888888", "StrongPass1!", "bad"),
                        request,
                        response,
                        currentUser))
                .withMessage("captcha incorrect")
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        verifyNoInteractions(tokenTransport);
    }

    @Test
    void updatePasswordPropagatesMissingAuthenticatedUserFromApplicationService() {
        MockHttpServletRequest request = requestWithCaptcha();
        MockHttpServletResponse response = new MockHttpServletResponse();
        doThrow(new BusinessException(ErrorCode.UNAUTHORIZED, "login required"))
                .when(passwordChangeService)
                .changePassword(null, CAPTCHA_CHALLENGE_ID, "1234", "18888888888", "StrongPass1!", "127.0.0.1");

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> controller.updatePassword(
                        new PasswordChangeRequestDto("18888888888", "StrongPass1!", "1234"), request, response, null))
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));

        verifyNoInteractions(captchaService, tokenTransport);
    }

    private static MockHttpServletRequest requestWithCaptcha() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.setCookies(new Cookie(CaptchaHttp.CAPTCHA_ID_COOKIE, CAPTCHA_CHALLENGE_ID));
        return request;
    }
}
