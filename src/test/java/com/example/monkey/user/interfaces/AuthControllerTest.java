package com.example.monkey.user.interfaces;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.example.monkey.shared.application.security.SessionTokenPair;
import com.example.monkey.shared.application.storage.UploadFileContent;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.shared.interfaces.dto.Result;
import com.example.monkey.shared.interfaces.web.CaptchaHttp;
import com.example.monkey.shared.interfaces.web.GlobalExceptionHandler;
import com.example.monkey.shared.interfaces.web.SessionTokenTransport;
import com.example.monkey.user.application.AuthResponseService;
import com.example.monkey.user.application.CaptchaService;
import com.example.monkey.user.application.LoginApplicationService;
import com.example.monkey.user.application.LoginApplicationService.LoginResult;
import com.example.monkey.user.application.PasswordPolicyQueryService;
import com.example.monkey.user.application.PasswordResetApplicationService;
import com.example.monkey.user.application.PasswordResetApplicationService.PasswordResetResult;
import com.example.monkey.user.application.RefreshTokenApplicationService;
import com.example.monkey.user.application.RefreshTokenApplicationService.RefreshTokenFailure;
import com.example.monkey.user.application.RefreshTokenApplicationService.RefreshTokenResult;
import com.example.monkey.user.application.RegistrationApplicationService;
import com.example.monkey.user.application.dto.AuthLoginResponseDto;
import com.example.monkey.user.application.dto.PasswordPolicyResponseDto;
import com.example.monkey.user.interfaces.dto.LoginRequestDto;
import com.example.monkey.user.interfaces.dto.PasswordResetChallengeRequestDto;
import com.example.monkey.user.interfaces.dto.PasswordResetRequestDto;
import com.example.monkey.user.interfaces.dto.RegisterRequestDto;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MultipartFile;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {
    private static final String CAPTCHA_CHALLENGE_ID = "challenge-id";

    @Mock
    private CaptchaService captchaService;

    @Mock
    private RegistrationApplicationService registrationService;

    @Mock
    private RefreshTokenApplicationService refreshTokenService;

    @Mock
    private SessionTokenTransport tokenTransport;

    @Mock
    private LoginApplicationService loginService;

    @Mock
    private PasswordResetApplicationService passwordResetService;

    @Mock
    private PasswordPolicyQueryService passwordPolicyQueryService;

    @Test
    void returnsPasswordPolicyMetadata() throws Exception {
        MockMvc mockMvc = standaloneSetup(newController()).build();
        when(passwordPolicyQueryService.metadata())
                .thenReturn(new PasswordPolicyResponseDto(10, true, true, true, true, true));

        mockMvc.perform(get("/api/v1/auth/password-policy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.minLength").value(10))
                .andExpect(jsonPath("$.data.requireSpecial").value(true));
    }

    @Test
    void malformedLoginJsonReturnsMalformedProblem() throws Exception {
        MockMvc mockMvc = standaloneSetup(newController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("REQUEST_MALFORMED"));
    }

    @Test
    void invalidRegistrationReturnsStructuredValidationProblem() throws Exception {
        MockMvc mockMvc = standaloneSetup(newController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .param("username", "x"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").exists());
    }

    @Test
    void registerPropagatesApplicationCaptchaFailure() {
        AuthController controller = newController();
        MockHttpServletRequest request = requestWithCaptcha();
        doThrow(new BusinessException(ErrorCode.VALIDATION_ERROR, "captcha incorrect"))
                .when(registrationService)
                .register(
                        "alice",
                        "StrongPass1!",
                        "18888888888",
                        "alice@example.com",
                        CAPTCHA_CHALLENGE_ID,
                        "bad",
                        "127.0.0.1",
                        null);

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> controller.register(
                        register("alice", "StrongPass1!", "18888888888", "alice@example.com", "bad", null), request))
                .withMessage("captcha incorrect")
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    void registerDelegatesMultipartRegistrationToApplicationService() {
        AuthController controller = newController();
        MockHttpServletRequest request = requestWithCaptcha();
        MockMultipartFile avatar = new MockMultipartFile("avatarFile", "avatar.png", "image/png", new byte[] {1, 2, 3});

        Result<Void> result = controller.register(
                register("alice", "StrongPass1!", "18888888888", "alice@example.com", "1234", avatar), request);

        assertThat(result.code()).isEqualTo("OK");
        assertThat(result.data()).isNull();
        verify(registrationService)
                .register(
                        eq("alice"),
                        eq("StrongPass1!"),
                        eq("18888888888"),
                        eq("alice@example.com"),
                        eq(CAPTCHA_CHALLENGE_ID),
                        eq("1234"),
                        eq("127.0.0.1"),
                        any(UploadFileContent.class));
    }

    @Test
    void requestPasswordResetDelegatesToApplicationService() {
        AuthController controller = newController();
        MockHttpServletRequest request = requestWithCaptcha();

        Result<Void> result = controller.requestPasswordReset(
                new PasswordResetChallengeRequestDto("alice", "18888888888", "alice@example.com", "captcha-token"),
                request);

        assertThat(result.code()).isEqualTo("OK");
        assertThat(result.data()).isNull();
        verify(passwordResetService)
                .requestPasswordReset(
                        "alice",
                        "18888888888",
                        "alice@example.com",
                        "captcha-token",
                        CAPTCHA_CHALLENGE_ID,
                        "127.0.0.1");
    }

    @Test
    void requestPasswordResetPropagatesApplicationFailure() {
        AuthController controller = newController();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        doThrow(new BusinessException(ErrorCode.RATE_LIMIT, "too many reset requests"))
                .when(passwordResetService)
                .requestPasswordReset("alice", "18888888888", null, null, null, "127.0.0.1");

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(
                        () -> controller.requestPasswordReset(resetChallenge("alice", "18888888888", null), request))
                .withMessage("too many reset requests")
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.RATE_LIMIT));
    }

    @Test
    void resetPasswordDelegatesToApplicationServiceAndRevokesTokens() {
        AuthController controller = newController();
        MockHttpServletRequest request = requestWithCaptcha();
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(passwordResetService.resetPassword(
                        "alice",
                        "18888888888",
                        null,
                        "654321",
                        null,
                        "StrongPass1!",
                        null,
                        CAPTCHA_CHALLENGE_ID,
                        "127.0.0.1"))
                .thenReturn(new PasswordResetResult(7L));

        Result<Void> result = controller.resetPassword(
                resetPassword("alice", "18888888888", null, "654321", null, "StrongPass1!"), request, response);

        assertThat(result.code()).isEqualTo("OK");
        assertThat(result.data()).isNull();
        verify(tokenTransport).revokeUserTokens(7L, response);
    }

    @Test
    void resetPasswordDoesNotRevokeTokensWhenApplicationReturnsNoUserId() {
        AuthController controller = newController();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(passwordResetService.resetPassword(
                        "alice", "18888888888", null, "654321", null, "StrongPass1!", null, null, "127.0.0.1"))
                .thenReturn(new PasswordResetResult(null));

        Result<Void> result = controller.resetPassword(
                resetPassword("alice", "18888888888", null, "654321", null, "StrongPass1!"), request, response);

        assertThat(result.code()).isEqualTo("OK");
        assertThat(result.data()).isNull();
        verify(tokenTransport, never()).revokeUserTokens(7L, response);
    }

    @Test
    void resetPasswordPropagatesApplicationFailureWithoutRevokingTokens() {
        AuthController controller = newController();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        doThrow(new BusinessException(ErrorCode.VALIDATION_ERROR, "invalid or expired reset otp"))
                .when(passwordResetService)
                .resetPassword("alice", "18888888888", null, "654321", null, "StrongPass1!", null, null, "127.0.0.1");

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> controller.resetPassword(
                        resetPassword("alice", "18888888888", null, "654321", null, "StrongPass1!"), request, response))
                .withMessage("invalid or expired reset otp")
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        verify(tokenTransport, never()).revokeUserTokens(7L, response);
    }

    @Test
    void loginDelegatesToApplicationServiceAppliesJwtCookiesAndReturnsEnvelope() {
        AuthController controller = newController();
        MockHttpServletRequest request = requestWithCaptcha();
        request.addHeader("X-Forwarded-For", "203.0.113.10, 127.0.0.1");
        String originalSessionId = request.getSession(true).getId();
        MockHttpServletResponse response = new MockHttpServletResponse();
        SessionTokenPair tokenPair =
                new SessionTokenPair("access-token", "refresh-token", "access-id", "refresh-id", 900, 604800);
        when(loginService.login("alice", "StrongPass1!", "1234", null, CAPTCHA_CHALLENGE_ID, "203.0.113.10"))
                .thenReturn(new LoginResult(tokenPair, new AuthLoginResponseDto("USER", false)));

        Result<AuthLoginResponseDto> result =
                controller.login(login("alice", "StrongPass1!", "1234", null), request, response);

        assertThat(result.code()).isEqualTo("OK");
        assertThat(result.data().role()).isEqualTo("USER");
        assertThat(result.data().passwordChangeRequired()).isFalse();
        var rotatedSession = request.getSession(false);
        assertThat(rotatedSession).isNotNull();
        assertThat(rotatedSession.getId()).isNotEqualTo(originalSessionId);
        verify(tokenTransport).applyTokenCookies(response, tokenPair);
    }

    @Test
    void loginPropagatesApplicationFailureWithoutApplyingCookies() {
        AuthController controller = newController();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        doThrow(new BusinessException(ErrorCode.UNAUTHORIZED, "username or password is incorrect"))
                .when(loginService)
                .login("alice", "bad-password", null, null, null, "127.0.0.1");

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> controller.login(login("alice", "bad-password"), request, response))
                .withMessage("username or password is incorrect")
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
        verify(tokenTransport, never()).applyTokenCookies(any(), any(SessionTokenPair.class));
    }

    @Test
    void refreshDelegatesToApplicationServiceAppliesRotatedCookies() {
        AuthController controller = newController();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.10");
        MockHttpServletResponse response = new MockHttpServletResponse();
        SessionTokenPair tokenPair =
                new SessionTokenPair("access-token", "refresh-token", "access-id", "new-refresh-id", 900, 604800);
        when(tokenTransport.resolveRefreshToken(request)).thenReturn("old-refresh-token");
        when(refreshTokenService.refresh("old-refresh-token", "203.0.113.10"))
                .thenReturn(new RefreshTokenResult(tokenPair));

        Result<Void> result = controller.refresh(request, response);

        assertThat(result.code()).isEqualTo("OK");
        assertThat(result.data()).isNull();
        verify(refreshTokenService).refresh("old-refresh-token", "203.0.113.10");
        verify(tokenTransport).applyTokenCookies(response, tokenPair);
    }

    @Test
    void refreshClearsCookiesWhenApplicationRejectsTokenWithClearSignal() {
        AuthController controller = newController();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.14");
        MockHttpServletResponse response = new MockHttpServletResponse();
        RefreshTokenFailure failure =
                new RefreshTokenFailure(ErrorCode.UNAUTHORIZED, "invalid or expired refresh token", true);
        when(tokenTransport.resolveRefreshToken(request)).thenReturn("tampered-refresh-token");
        when(refreshTokenService.refresh("tampered-refresh-token", "203.0.113.14"))
                .thenThrow(failure);

        assertThatExceptionOfType(RefreshTokenFailure.class)
                .isThrownBy(() -> controller.refresh(request, response))
                .withMessage("invalid or expired refresh token")
                .satisfies(exception -> {
                    assertThat(exception.errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
                    assertThat(exception.clearTokenCookies()).isTrue();
                });
        verify(tokenTransport).clearTokenCookies(response);
        verify(tokenTransport, never()).applyTokenCookies(any(), any(SessionTokenPair.class));
    }

    @Test
    void refreshDoesNotClearCookiesWhenApplicationRejectsMissingToken() {
        AuthController controller = newController();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.13");
        MockHttpServletResponse response = new MockHttpServletResponse();
        RefreshTokenFailure failure =
                new RefreshTokenFailure(ErrorCode.UNAUTHORIZED, "refresh token not provided", false);
        when(tokenTransport.resolveRefreshToken(request)).thenReturn(null);
        when(refreshTokenService.refresh(null, "203.0.113.13")).thenThrow(failure);

        assertThatExceptionOfType(RefreshTokenFailure.class)
                .isThrownBy(() -> controller.refresh(request, response))
                .withMessage("refresh token not provided")
                .satisfies(exception -> {
                    assertThat(exception.errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
                    assertThat(exception.clearTokenCookies()).isFalse();
                });
        verify(tokenTransport, never()).clearTokenCookies(any());
        verify(tokenTransport, never()).applyTokenCookies(any(), any(SessionTokenPair.class));
    }

    private static PasswordResetChallengeRequestDto resetChallenge(String username, String phone, String email) {
        return new PasswordResetChallengeRequestDto(username, phone, email, null);
    }

    private static PasswordResetRequestDto resetPassword(
            String username, String phone, String email, String otp, String emailToken, String newPassword) {
        return new PasswordResetRequestDto(username, phone, email, otp, emailToken, newPassword, null);
    }

    private static LoginRequestDto login(String username, String password) {
        return login(username, password, null, null);
    }

    private static LoginRequestDto login(String username, String password, String captcha, String totp) {
        return new LoginRequestDto(username, password, captcha, totp);
    }

    private static MockHttpServletRequest requestWithCaptcha() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.setCookies(new Cookie(CaptchaHttp.CAPTCHA_ID_COOKIE, CAPTCHA_CHALLENGE_ID));
        return request;
    }

    private static RegisterRequestDto register(
            String username, String password, String phone, String email, String captcha, MultipartFile avatarFile) {
        return new RegisterRequestDto(username, password, phone, email, captcha, avatarFile);
    }

    private AuthController newController() {
        return new AuthController(
                captchaService,
                registrationService,
                loginService,
                refreshTokenService,
                tokenTransport,
                passwordResetService,
                new AuthResponseService(),
                passwordPolicyQueryService);
    }
}
